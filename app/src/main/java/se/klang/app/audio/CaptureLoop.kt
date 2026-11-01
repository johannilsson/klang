package se.klang.app.audio

import se.klang.sessions.AnalysisService
import se.klang.sessions.CaptureEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * What the capture loop needs from `AudioRecord`. The production implementation wraps an
 * `AudioRecord`; unit tests use a scripted fake.
 */
internal interface PcmReader {
    /**
     * Blocking read of up to [frames] mono frames into [out] starting at [offset], converted to
     * float. Returns the number of frames read, or a negative `AudioRecord` error code.
     */
    fun read(out: FloatArray, offset: Int, frames: Int): Int

    /** HAL frame position of the latest capture timestamp, or [NO_POSITION] when none is available. */
    fun framePosition(): Long

    companion object {
        const val NO_POSITION = Long.MIN_VALUE
    }
}

/**
 * The capture loop of one attempt: copies [PcmReader] frames into a preallocated array and turns
 * read failures into [CaptureEvent]s. No DSP, storage or UI work happens here; the only per-chunk
 * work besides the copy is the peak for the level meter and the overrun check.
 *
 * Events: READ_ERROR (negative read result; the attempt fails), SHORT_READ (fewer frames than
 * requested), OVERRUN (the HAL frame position ran ahead of what was read by more than the whole
 * buffer, so frames were dropped; reported once).
 */
internal class CaptureLoop(
    private val sampleRate: Int,
    private val bufferFrames: Long,
    private val events: MutableList<CaptureEvent>,
    /** Frames read so far; read by the routing and silencing callbacks on another thread. */
    private val framesRead: AtomicLong = AtomicLong(0),
) {
    sealed interface End {
        data object Completed : End
        data class Cancelled(val reason: String) : End
        data class Failed(val message: String) : End
    }

    /**
     * Fills [out] in 20 ms reads. [cancelReason] is polled before every read; a non-null value
     * stops the capture. [onProgress] gets elapsed seconds and the peak (dBFS) since the previous
     * call, about 20 times per second.
     */
    fun run(
        reader: PcmReader,
        out: FloatArray,
        cancelReason: () -> String?,
        onProgress: (elapsedSeconds: Double, peakDbfs: Double) -> Unit,
    ): End {
        val total = out.size
        val chunk = sampleRate / 50 // 20 ms reads
        var lagBaseline = 0L
        var haveBaseline = false
        var overrunReported = false
        var lastProgress = 0L
        var peakSince = 0f
        var pos = 0
        while (pos < total) {
            cancelReason()?.let { return End.Cancelled(it) }
            val n = min(chunk, total - pos)
            val got = reader.read(out, pos, n)
            if (got < 0) {
                events += CaptureEvent("READ_ERROR", pos.toLong(), "AudioRecord.read returned $got")
                return End.Failed("Capture failed (error $got)")
            }
            if (got < n) events += CaptureEvent("SHORT_READ", pos.toLong(), "$got of $n frames")
            for (i in pos until pos + max(0, got)) { val a = abs(out[i]); if (a > peakSince) peakSince = a }
            pos += got
            framesRead.set(pos.toLong())

            // Overrun heuristic: the HAL frame position running ahead of what we have read
            // by more than the whole buffer means frames were dropped.
            if (!overrunReported) {
                val framePosition = reader.framePosition()
                if (framePosition != PcmReader.NO_POSITION) {
                    val lag = framePosition - pos
                    if (!haveBaseline) { lagBaseline = lag; haveBaseline = true }
                    if (lag - lagBaseline > bufferFrames) {
                        events += CaptureEvent("OVERRUN", pos.toLong(), "Capture fell ${lag - lagBaseline} frames behind (buffer $bufferFrames)")
                        overrunReported = true
                    }
                }
            }
            if (pos - lastProgress >= sampleRate / 20) {
                lastProgress = pos.toLong()
                onProgress(pos.toDouble() / sampleRate, if (peakSince > 0f) 20.0 * log10(peakSince.toDouble()) else -120.0)
                peakSince = 0f
            }
        }
        return End.Completed
    }
}

/**
 * Turns input-routing callbacks into ROUTE_CHANGED events. The first route seen (from the first
 * callback or from [start]) is the initial route; only later differences are changes. Thread-safe.
 */
internal class RouteTracker {
    private val initialDeviceId = AtomicInteger(NO_DEVICE)

    /** Records the route read after the recording started, unless a callback already reported one. */
    fun start(deviceId: Int) { initialDeviceId.compareAndSet(NO_DEVICE, deviceId) }

    /**
     * A routing callback with the routed device's id (null: no input device). Returns the event to
     * record, or null when the route is the initial one.
     */
    fun onRouted(deviceId: Int?, description: String, atFrame: Long): CaptureEvent? {
        if (deviceId != null && initialDeviceId.compareAndSet(NO_DEVICE, deviceId)) return null
        if (deviceId == null || deviceId != initialDeviceId.get()) {
            return CaptureEvent("ROUTE_CHANGED", atFrame, if (deviceId == null) "no input device" else description)
        }
        return null
    }

    private companion object {
        const val NO_DEVICE = Int.MIN_VALUE
    }
}

/** Pure mapping of capture diagnostics to [CaptureEvent]s and analysis input. */
internal object CaptureDiagnostics {
    /** The system silenced this client (another app or a call took the microphone). */
    fun silenced(isClientSilenced: Boolean, atFrame: Long): CaptureEvent? =
        if (isClientSilenced) CaptureEvent("SILENCED", atFrame, "System silenced this capture (another app or a call took the microphone)") else null

    /**
     * Safety net when no routing callback fired: the device routed at the end differs from the one
     * at the start. Ids and names are null when the route could not be read.
     */
    fun routeChangedAtEnd(
        startId: Int?, startName: String?, endId: Int?, endName: String?,
        events: List<CaptureEvent>, atFrame: Long,
    ): CaptureEvent? =
        if (startId != null && endId != null && startId != endId && events.none { it.type == "ROUTE_CHANGED" }) {
            CaptureEvent("ROUTE_CHANGED", atFrame, "Input device changed from $startName to $endName")
        } else null

    /**
     * Frames where the capture timeline broke, for `PcmSignal.discontinuities`. Same event types
     * and mapping as the stored-attempt analysis in [AnalysisService].
     */
    fun discontinuityFrames(events: List<CaptureEvent>): List<Int> = events
        .filter { it.type in AnalysisService.DISCONTINUITY_EVENTS }
        .map { it.atFrame.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
}
