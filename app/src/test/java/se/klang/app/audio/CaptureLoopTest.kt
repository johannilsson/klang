package se.klang.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.klang.sessions.CaptureEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * The capture loop's mapping of `AudioRecord` read results to capture events, with a scripted
 * reader instead of a microphone.
 */
class CaptureLoopTest {

    private val rate = 1000 // 20 frames per 20 ms read keeps the scripts short
    private val chunk = rate / 50

    /** Each call to [read] takes the next scripted result: frames to deliver (≤ requested) or an error code. */
    private class ScriptedReader(
        private val results: List<(requested: Int) -> Int>,
        private val positions: (call: Int, framesSoFar: Int) -> Long = { _, _ -> PcmReader.NO_POSITION },
        private val value: Float = 0.5f,
    ) : PcmReader {
        var reads = 0
        var positionQueries = 0
        var framesSoFar = 0
        val arrays = mutableSetOf<FloatArray>()

        override fun read(out: FloatArray, offset: Int, frames: Int): Int {
            arrays += out
            val r = results.getOrElse(reads) { { n: Int -> n } }(frames)
            reads++
            for (i in 0 until maxOf(0, r)) out[offset + i] = value
            if (r > 0) framesSoFar += r
            return r
        }

        override fun framePosition(): Long = positions(positionQueries++, framesSoFar)
    }

    private fun run(
        reader: ScriptedReader, frames: Int, bufferFrames: Long = 100,
        cancel: () -> String? = { null },
        progress: MutableList<Pair<Double, Double>> = mutableListOf(),
        maxZeroReads: Int = CaptureLoop.DEFAULT_MAX_CONSECUTIVE_ZERO_READS,
    ): Triple<CaptureLoop.End, List<CaptureEvent>, FloatArray> {
        val events = mutableListOf<CaptureEvent>()
        val out = FloatArray(frames)
        val end = CaptureLoop(rate, bufferFrames, events, AtomicLong(), maxZeroReads).run(reader, out, cancel) { t, p -> progress += t to p }
        return Triple(end, events, out)
    }

    @Test
    fun fullReadsCompleteWithoutEventsIntoTheGivenArray() {
        val reader = ScriptedReader(emptyList())
        val framesRead = AtomicLong()
        val events = mutableListOf<CaptureEvent>()
        val out = FloatArray(rate)
        val end = CaptureLoop(rate, 100, events, framesRead).run(reader, out, { null }) { _, _ -> }
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(emptyList<CaptureEvent>(), events)
        assertEquals(rate / chunk, reader.reads)
        assertEquals(rate.toLong(), framesRead.get())
        assertTrue(out.all { it == 0.5f })
        // Samples are copied straight into the caller's preallocated array, never into a copy.
        assertSame(out, reader.arrays.single())
    }

    @Test
    fun shortReadIsReportedAtItsFrameAndTheLoopContinues() {
        val reader = ScriptedReader(listOf({ n -> n }, { n -> n }, { 7 }))
        val (end, events, out) = run(reader, rate)
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(listOf(CaptureEvent("SHORT_READ", 2L * chunk, "7 of $chunk frames")), events)
        assertTrue("the remaining frames are still read", out.all { it == 0.5f })
        assertEquals(CaptureDiagnostics.discontinuityFrames(events), listOf(2 * chunk))
    }

    @Test
    fun aRunOfZeroReadsIsReportedOnceAndTheLoopContinues() {
        val zero: (Int) -> Int = { 0 }
        val reader = ScriptedReader(listOf<(Int) -> Int>({ n -> n }) + List(10) { zero })
        val (end, events, out) = run(reader, rate, maxZeroReads = 50)
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(listOf(CaptureEvent("SHORT_READ", chunk.toLong(), "0 of $chunk frames")), events)
        assertTrue("the recording is still filled once reads resume", out.all { it == 0.5f })
        assertEquals(rate / chunk + 10, reader.reads)
    }

    @Test
    fun separateRunsOfZeroReadsAreEachReportedOnce() {
        val zero: (Int) -> Int = { 0 }
        val full: (Int) -> Int = { n -> n }
        val reader = ScriptedReader(listOf(zero, zero, full, full, zero, zero, zero))
        val (end, events, _) = run(reader, rate, maxZeroReads = 3)
        assertEquals("two zeros, then three zeros with a limit of 3: the second run ends the attempt", true, end is CaptureLoop.End.Failed)
        assertEquals(listOf(CaptureEvent("SHORT_READ", 0, "0 of $chunk frames"), CaptureEvent("SHORT_READ", 2L * chunk, "0 of $chunk frames")), events)
    }

    @Test
    fun zeroReadsBelowTheLimitDoNotEndTheAttempt() {
        val zero: (Int) -> Int = { 0 }
        val full: (Int) -> Int = { n -> n }
        // Runs of 4 zero reads, each followed by a full read: never 5 in a row.
        val reader = ScriptedReader(List(rate / chunk) { List(4) { zero } + full }.flatten())
        val (end, events, _) = run(reader, rate, maxZeroReads = 5)
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(rate / chunk, events.size)
        assertTrue(events.all { it.type == "SHORT_READ" })
    }

    @Test
    fun zeroReadsThatNeverStopEndTheAttemptAfterTheLimit() {
        val reader = ScriptedReader(listOf<(Int) -> Int>({ n -> n }) + List(1000) { { 0 } })
        val (end, events, _) = run(reader, rate, maxZeroReads = 50)
        assertEquals(CaptureLoop.End.Failed("The microphone delivered no audio (50 reads in a row returned 0 frames)"), end)
        assertEquals("bounded: one full read and 50 zero reads, then the loop stops", 51, reader.reads)
        assertEquals(listOf(CaptureEvent("SHORT_READ", chunk.toLong(), "0 of $chunk frames")), events)
    }

    @Test
    fun lastPartialChunkRequestsOnlyWhatIsLeftAndIsNotAShortRead() {
        val reader = ScriptedReader(emptyList())
        val (end, events, _) = run(reader, chunk * 3 + 5)
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(emptyList<CaptureEvent>(), events)
        assertEquals(4, reader.reads)
    }

    @Test
    fun readErrorFailsTheAttemptAndStopsReading() {
        val reader = ScriptedReader(listOf({ n -> n }, { -3 }))
        val (end, events, _) = run(reader, rate)
        assertEquals(CaptureLoop.End.Failed("Capture failed (error -3)"), end)
        assertEquals(listOf(CaptureEvent("READ_ERROR", chunk.toLong(), "AudioRecord.read returned -3")), events)
        assertEquals(2, reader.reads)
        assertEquals("a read error is not a discontinuity: the attempt is never analysed", emptyList<Int>(), CaptureDiagnostics.discontinuityFrames(events))
    }

    @Test
    fun overrunIsReportedOnceWhenTheHalRunsAheadByMoreThanTheBuffer() {
        // Lag baseline 10 frames; from the 5th read on the HAL is 150 frames further ahead.
        val reader = ScriptedReader(emptyList(), positions = { call, frames -> frames + 10L + if (call >= 4) 150 else 0 })
        val (end, events, _) = run(reader, rate, bufferFrames = 100)
        assertEquals(CaptureLoop.End.Completed, end)
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("OVERRUN", e.type)
        assertEquals(5L * chunk, e.atFrame)
        assertEquals("Capture fell 150 frames behind (buffer 100)", e.detail)
        assertEquals("the timestamp is not queried again after the report", 5, reader.positionQueries)
    }

    @Test
    fun lagWithinTheBufferOrNoTimestampIsNotAnOverrun() {
        val within = ScriptedReader(emptyList(), positions = { call, frames -> frames + if (call >= 4) 100L else 0 })
        assertEquals(emptyList<CaptureEvent>(), run(within, rate, bufferFrames = 100).second)
        val none = ScriptedReader(emptyList())
        assertEquals(emptyList<CaptureEvent>(), run(none, rate).second)
    }

    @Test
    fun cancellationStopsBeforeTheNextRead() {
        val reader = ScriptedReader(emptyList())
        val (end, events, _) = run(reader, rate, cancel = { if (reader.reads >= 3) "INTERRUPTED" else null })
        assertEquals(CaptureLoop.End.Cancelled("INTERRUPTED"), end)
        assertEquals(3, reader.reads)
        assertEquals(emptyList<CaptureEvent>(), events)
    }

    @Test
    fun progressReportsElapsedTimeAndPeakAboutTwentyTimesPerSecond() {
        val progress = mutableListOf<Pair<Double, Double>>()
        run(ScriptedReader(emptyList()), rate, progress = progress)
        // 50 frames (rate / 20) between reports, read in 20-frame chunks: every 60 frames.
        assertEquals(16, progress.size)
        assertEquals(0.06, progress.first().first, 1e-9)
        assertEquals(20.0 * kotlin.math.log10(0.5), progress.first().second, 1e-6)
    }

    @Test
    fun silentChunksReportTheFloorLevel() {
        val progress = mutableListOf<Pair<Double, Double>>()
        val events = mutableListOf<CaptureEvent>()
        CaptureLoop(rate, 100, events).run(ScriptedReader(emptyList(), value = 0f), FloatArray(rate), { null }) { t, p -> progress += t to p }
        assertTrue(progress.all { it.second == -120.0 })
    }
}
