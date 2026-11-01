package se.klang.sessions

import se.klang.dsp.Levels
import kotlin.math.max

/** A sound in the recording that is louder than the room, found for display. */
data class SoundEvent(val timeSeconds: Double, val levelDbfs: Double, val kind: Kind) {
    enum class Kind {
        /** In the quiet part before the clap (a tap, movement, a second clap). */
        BEFORE_CLAP,
        /** After the clap had faded into the background: it cannot change a band's result. */
        AFTER_DECAY,
    }
}

/**
 * The level of a recording over time, for the graph on the attempt screen and the small graphs on the
 * session screen, plus the sounds in it that are louder than the room.
 *
 * [levelsDbfs] is the short-time RMS level (mean-square convention, like the background level of an
 * analysis) per [stepSeconds] window, in dBFS (relative to digital full scale, never SPL), so the
 * background line and the curve agree. [peakDbfs] is the instantaneous sample peak.
 *
 * **Display only.** The [events] mark where a loud sound is so the graph can show it; whether a
 * recording is usable comes from the analysis (its reasons), never from this. The rules are simple on
 * purpose: a window [policy.eventAboveBackgroundDb] louder than the typical (median) quiet window.
 */
class RecordingTimeline(
    val stepSeconds: Double,
    val levelsDbfs: FloatArray,
    val durationSeconds: Double,
    val onsetSeconds: Double?,
    val peakDbfs: Double,
    /** The analysis' background RMS level, else the median quiet window; null when unknown. */
    val backgroundDbfs: Double?,
    val events: List<SoundEvent>,
    /**
     * The typical level of the quiet part of the recording (the median window before the clap; for a recording
     * without a clap, the median window). Unlike [backgroundDbfs], a loud sound in the quiet part does not raise it.
     */
    val quietDbfs: Double? = null,
) {
    data class Policy(
        val stepSeconds: Double = 0.05,
        /** Levels below this are drawn at this floor (silence). */
        val floorDbfs: Double = -100.0,
        /** A window this far above the typical quiet window is a sound. */
        val eventAboveBackgroundDb: Double = 15.0,
        /** The clap has faded into the background when the level is within this of the typical quiet window. */
        val decayEndWithinDb: Double = 6.0,
        /** Ignored at the start of the recording (microphone start-up). */
        val startupSeconds: Double = 0.1,
    )

    companion object {
        fun of(samples: FloatArray, sampleRate: Int, analysis: AnalysisRecord?, policy: Policy = Policy()): RecordingTimeline {
            val win = max(1, (policy.stepSeconds * sampleRate).toInt())
            val n = samples.size / win
            val levels = FloatArray(n) { w ->
                max(policy.floorDbfs, Levels.rmsDbfs(samples, w * win, (w + 1) * win)).toFloat()
            }
            val duration = samples.size.toDouble() / sampleRate
            val onset = analysis?.onsetSeconds
            val peak = analysis?.peakDbfs ?: Levels.peakDbfs(samples)

            // The typical quiet window: the median of the windows before the clap.
            val firstQuiet = ceilDiv(policy.startupSeconds, policy.stepSeconds)
            val lastQuiet = onset?.let { ((it - 0.02) / policy.stepSeconds).toInt() - 1 }
            val quiet = if (lastQuiet != null && lastQuiet > firstQuiet) levels.slice(firstQuiet..minOf(lastQuiet, n - 1)) else emptyList()
            val quietMedian = quiet.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2].toDouble() }
            val events = if (onset != null && quietMedian != null) events(levels, onset, quietMedian, firstQuiet, policy) else emptyList()
            // A recording without a clap (a background recording) has no quiet part to pick out: its typical level is the median window.
            val overall = if (onset == null && n > firstQuiet) levels.slice(firstQuiet until n).sorted().let { it[it.size / 2].toDouble() } else null
            return RecordingTimeline(policy.stepSeconds, levels, duration, onset, peak, analysis?.backgroundRmsDbfs ?: quietMedian ?: overall, events, quietMedian ?: overall)
        }

        private fun ceilDiv(a: Double, b: Double) = kotlin.math.ceil(a / b - 1e-9).toInt()

        private fun events(levels: FloatArray, onset: Double, quiet: Double, firstQuiet: Int, p: Policy): List<SoundEvent> {
            val out = ArrayList<SoundEvent>()
            val loud = quiet + p.eventAboveBackgroundDb
            val onsetWindow = (onset / p.stepSeconds).toInt()

            fun groups(from: Int, to: Int, kind: SoundEvent.Kind) {
                var i = from
                while (i < to) {
                    if (levels[i] >= loud) {
                        var j = i
                        var top = levels[i]
                        while (j + 1 < to && levels[j + 1] >= loud) { j++; top = max(top, levels[j]) }
                        out += SoundEvent(i * p.stepSeconds, top.toDouble(), kind)
                        i = j + 1
                    } else i++
                }
            }

            // Before the clap: from the end of the start-up to just before the onset.
            groups(firstQuiet, minOf(levels.size, ((onset - 0.02) / p.stepSeconds).toInt()), SoundEvent.Kind.BEFORE_CLAP)

            // After the decay: from the first moment the level is back near the background for two windows.
            val near = quiet + p.decayEndWithinDb
            var end = -1
            var k = onsetWindow + 2
            while (k + 1 < levels.size) {
                if (levels[k] <= near && levels[k + 1] <= near) { end = k; break }
                k++
            }
            if (end >= 0) groups(end + 2, levels.size, SoundEvent.Kind.AFTER_DECAY)
            return out
        }
    }
}
