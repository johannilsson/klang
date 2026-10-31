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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

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
 *   thread. Conversion, analysis and storage happen after capture ends.
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
     * Records [durationSeconds] of audio. [onProgress] receives elapsed seconds (called from the
     * capture thread at most ~20 times per second; must be cheap and thread-safe).
     */
    @SuppressLint("MissingPermission") // checked explicitly below
    suspend fun record(durationSeconds: Double, onProgress: (Double) -> Unit = {}): Outcome = withContext(captureThread) {
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
            val shortBuf = if (isFloat) null else ShortArray(rate / 50)
            val chunk = rate / 50 // 20 ms reads

            val initialDeviceId = AtomicInteger(NO_DEVICE)
            val routingListener = AudioRouting.OnRoutingChangedListener { router ->
                val d = router.routedDevice
                // The first callback reports the initial route; only later differences are changes.
                if (d != null && initialDeviceId.compareAndSet(NO_DEVICE, d.id)) return@OnRoutingChangedListener
                if (d == null || d.id != initialDeviceId.get()) {
                    events += CaptureEvent("ROUTE_CHANGED", framesRead.get(), d?.let { "${AudioCapabilities.typeName(it.type)} ${it.productName}" } ?: "no input device")
                }
            }
            record.addOnRoutingChangedListener(routingListener, handler)
            val recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                    if (mine.isClientSilenced) events += CaptureEvent("SILENCED", framesRead.get(), "System silenced this capture (another app or a call took the microphone)")
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
                routedAtStart?.let { initialDeviceId.compareAndSet(NO_DEVICE, it.id) }
                notes += "bufferSizeInFrames=${record.bufferSizeInFrames}"
                record.activeRecordingConfiguration?.let { describeConfig(it, notes) }
                    ?: notes.add("activeRecordingConfiguration unavailable")

                val ts = AudioTimestamp()
                var lagBaseline: Long? = null
                val bufferFrames = record.bufferSizeInFrames.toLong()
                var overrunReported = false
                var lastProgress = 0L
                var pos = 0
                while (pos < total) {
                    cancelReason?.let { return@withContext Outcome.Cancelled(it) }
                    val n = min(chunk, total - pos)
                    val got = if (isFloat) {
                        record.read(out, pos, n, AudioRecord.READ_BLOCKING)
                    } else {
                        val r = record.read(shortBuf!!, 0, n, AudioRecord.READ_BLOCKING)
                        for (i in 0 until max(0, r)) out[pos + i] = shortBuf[i] / 32768f
                        r
                    }
                    if (got < 0) {
                        events += CaptureEvent("READ_ERROR", pos.toLong(), "AudioRecord.read returned $got")
                        return@withContext Outcome.Failed("Capture failed (error $got)", events.toList())
                    }
                    if (got < n) events += CaptureEvent("SHORT_READ", pos.toLong(), "$got of $n frames")
                    pos += got
                    framesRead.set(pos.toLong())

                    // Overrun heuristic: the HAL frame position running ahead of what we have read
                    // by more than the whole buffer means frames were dropped.
                    if (!overrunReported && record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val lag = ts.framePosition - pos
                        val base = lagBaseline ?: lag.also { lagBaseline = it }
                        if (lag - base > bufferFrames) {
                            events += CaptureEvent("OVERRUN", pos.toLong(), "Capture fell ${lag - base} frames behind (buffer $bufferFrames)")
                            overrunReported = true
                        }
                    }
                    if (pos - lastProgress >= rate / 20) { lastProgress = pos.toLong(); onProgress(pos.toDouble() / rate) }
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
            if (routedAtStart != null && routedAtEnd != null && routedAtStart.id != routedAtEnd.id && events.none { it.type == "ROUTE_CHANGED" }) {
                events += CaptureEvent("ROUTE_CHANGED", framesRead.get(), "Input device changed from ${routedAtStart.productName} to ${routedAtEnd.productName}")
            }
            Outcome.Completed(out, rate, config, events.toList())
        } finally {
            effectHandles.forEach { runCatching { it.release() } }
            record.release()
        }
    }

    private companion object {
        const val NO_DEVICE = Int.MIN_VALUE
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
