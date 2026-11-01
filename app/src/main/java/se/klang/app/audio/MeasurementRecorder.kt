package se.klang.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import se.klang.sessions.AudioConfiguration
import se.klang.sessions.CaptureEvent
import se.klang.sessions.EffectState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Fixed-length mono PCM capture for one measurement attempt.
 *
 * - Source: UNPROCESSED only if the device reports PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=true,
 *   otherwise VOICE_RECOGNITION, and the fallback is recorded (never assumed to be raw).
 * - Format: 48 kHz PCM float preferred, then PCM16, then 44.1 kHz. The *actual* format is recorded.
 * - Controllable pre-processing (AGC, NS, AEC) is disabled where the device offers it, and its
 *   state is recorded. That does not prove all vendor processing is off.
 * - Prefers the built-in microphone and records the routed device; route changes, silencing by the
 *   system, read errors and suspected overruns become [CaptureEvent]s that invalidate the attempt.
 * - The capture loop only copies samples into a preallocated array on a dedicated urgent-audio
 *   thread ([CaptureLoop]). Conversion, analysis and storage happen after capture ends.
 */
class MeasurementRecorder(private val context: Context) {

    sealed interface Outcome {
        data class Completed(
            val samples: FloatArray,
            val sampleRate: Int,
            val configuration: AudioConfiguration,
            val events: List<CaptureEvent>,
        ) : Outcome

        data class Failed(val message: String, val events: List<CaptureEvent> = emptyList()) : Outcome
        data class Cancelled(val reason: String) : Outcome
    }

    private val captureThread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "klang-capture")
    }.asCoroutineDispatcher()

    @Volatile private var cancelReason: String? = null

    /** Requests the running capture to stop; the attempt is reported as cancelled or interrupted. */
    fun cancel(reason: String) { cancelReason = reason }

    /**
     * Records [durationSeconds] of audio. [onProgress] receives elapsed seconds and the peak level
     * (dBFS) since the previous call (called from the capture thread ~20 times per second; must be
     * cheap and thread-safe).
     */
    @SuppressLint("MissingPermission") // checked explicitly below
    suspend fun record(durationSeconds: Double, onProgress: (elapsedSeconds: Double, peakDbfs: Double) -> Unit = { _, _ -> }): Outcome = withContext(captureThread) {
        cancelReason = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Outcome.Failed("Microphone permission not granted")
        }
        val am = context.getSystemService(AudioManager::class.java)
        val caps = AudioCapabilities.probe(context)
        val source = if (caps.unprocessedSupported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val sourceName = if (caps.unprocessedSupported) "UNPROCESSED" else "VOICE_RECOGNITION"

        val record = open(source) ?: return@withContext Outcome.Failed("Could not open the microphone with any supported format")
        val events = CopyOnWriteArrayList<CaptureEvent>()
        val handler = Handler(Looper.getMainLooper())
        val framesRead = AtomicLong(0) // written by the capture thread, read by callbacks
        val effects = ArrayList<EffectState>()
        val effectHandles = ArrayList<AudioEffect>()

        try {
            AudioCapabilities.builtInMic(context)?.let { record.setPreferredDevice(it) }
            disableEffects(record.audioSessionId, effects, effectHandles)

            val rate = record.sampleRate
            val isFloat = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
            val total = (durationSeconds * rate).toInt()
            val out = FloatArray(total)
            val reader = AudioRecordReader(record, isFloat, rate / 50)

            val routes = RouteTracker()
            val routingListener = AudioRouting.OnRoutingChangedListener { router ->
                val d = router.routedDevice
                routes.onRouted(d?.id, d?.let { "${AudioCapabilities.typeName(it.type)} ${it.productName}" }.orEmpty(), framesRead.get())
                    ?.let { events += it }
            }
            record.addOnRoutingChangedListener(routingListener, handler)
            val recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                    CaptureDiagnostics.silenced(mine.isClientSilenced, framesRead.get())?.let { events += it }
                }
            }
            am.registerAudioRecordingCallback(recordingCallback, handler)

            // Route and platform configuration are read while recording: after stop() they may be null.
            val notes = ArrayList<String>()
            var routedAtStart: android.media.AudioDeviceInfo? = null
            var routedAtEnd: android.media.AudioDeviceInfo? = null
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@withContext Outcome.Failed("Microphone did not start (is another app recording?)")
                }
                routedAtStart = record.routedDevice
                routedAtStart?.let { routes.start(it.id) }
                notes += "bufferSizeInFrames=${record.bufferSizeInFrames}"
                record.activeRecordingConfiguration?.let { describeConfig(it, notes) }
                    ?: notes.add("activeRecordingConfiguration unavailable")

                val loop = CaptureLoop(rate, record.bufferSizeInFrames.toLong(), events, framesRead)
                when (val end = loop.run(reader, out, { cancelReason }, onProgress)) {
                    is CaptureLoop.End.Cancelled -> return@withContext Outcome.Cancelled(end.reason)
                    is CaptureLoop.End.Failed -> return@withContext Outcome.Failed(end.message, events.toList())
                    CaptureLoop.End.Completed -> Unit
                }
                routedAtEnd = record.routedDevice
            } finally {
                runCatching { record.stop() }
                am.unregisterAudioRecordingCallback(recordingCallback)
                record.removeOnRoutingChangedListener(routingListener)
            }

            val routed = routedAtEnd ?: routedAtStart
            val config = AudioConfiguration(
                requestedSampleRate = 48_000,
                actualSampleRate = rate,
                encoding = if (isFloat) "PCM_FLOAT" else "PCM_16BIT",
                channelCount = record.channelCount,
                audioSource = sourceName,
                unprocessedSupported = caps.unprocessedSupported,
                sourceFallback = !caps.unprocessedSupported,
                inputDevice = routed?.let(AudioCapabilities::describe),
                effects = effects,
                recordingConfigNotes = notes,
                bufferSizeFrames = record.bufferSizeInFrames,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                androidSdkInt = Build.VERSION.SDK_INT,
            )
            CaptureDiagnostics.routeChangedAtEnd(
                routedAtStart?.id, routedAtStart?.productName?.toString(), routedAtEnd?.id, routedAtEnd?.productName?.toString(),
                events, framesRead.get(),
            )?.let { events += it }
            Outcome.Completed(out, rate, config, events.toList())
        } finally {
            effectHandles.forEach { runCatching { it.release() } }
            record.release()
        }
    }

    /** [PcmReader] over an [AudioRecord]: float reads directly, PCM16 through a preallocated buffer. */
    private class AudioRecordReader(private val record: AudioRecord, isFloat: Boolean, chunkFrames: Int) : PcmReader {
        private val shortBuf = if (isFloat) null else ShortArray(chunkFrames)
        private val ts = AudioTimestamp()

        override fun read(out: FloatArray, offset: Int, frames: Int): Int {
            val buf = shortBuf ?: return record.read(out, offset, frames, AudioRecord.READ_BLOCKING)
            val r = record.read(buf, 0, frames, AudioRecord.READ_BLOCKING)
            for (i in 0 until max(0, r)) out[offset + i] = buf[i] / 32768f
            return r
        }

        override fun framePosition(): Long =
            if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) ts.framePosition else PcmReader.NO_POSITION
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val attempts = buildList {
            for (rate in AudioCapabilities.CANDIDATE_RATES) {
                add(rate to AudioFormat.ENCODING_PCM_FLOAT)
                add(rate to AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        for ((rate, enc) in attempts) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, enc)
            if (min <= 0) continue
            val bytesPerFrame = if (enc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            // Generous buffer (≥ 0.5 s) so a brief scheduling hiccup does not drop samples.
            val size = max(min * 4, rate / 2 * bytesPerFrame)
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                    )
                    .setBufferSizeInBytes(size)
                    .build()
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            r.release()
        }
        return null
    }

    private fun disableEffects(sessionId: Int, out: MutableList<EffectState>, handles: MutableList<AudioEffect>) {
        fun handle(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) { out += EffectState(name, available = false, enabled = null); return }
            val e = runCatching { create() }.getOrNull()
            if (e == null) { out += EffectState(name, true, null, "available but could not be attached"); return }
            handles += e
            val rc = runCatching { e.setEnabled(false) }.getOrDefault(AudioEffect.ERROR)
            out += EffectState(name, true, e.enabled, if (rc == AudioEffect.SUCCESS) "" else "disable returned $rc")
        }
        handle("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
        handle("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        handle("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
    }

    private fun describeConfig(c: AudioRecordingConfiguration, notes: MutableList<String>) {
        notes += "clientSource=${c.clientAudioSource}"
        notes += "clientFormat=${c.clientFormat.sampleRate}Hz enc=${c.clientFormat.encoding} ch=${c.clientFormat.channelCount}"
        notes += "deviceFormat=${c.format.sampleRate}Hz enc=${c.format.encoding} ch=${c.format.channelCount}"
        notes += "silenced=${c.isClientSilenced}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            notes += "clientEffects=" + c.clientEffects.joinToString { it.name }
            notes += "deviceEffects=" + c.effects.joinToString { it.name }
        }
    }
}
