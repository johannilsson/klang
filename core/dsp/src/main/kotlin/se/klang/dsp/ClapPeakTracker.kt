package se.klang.dsp

import kotlin.math.max

/**
 * Groups a stream of short-window peak levels (dBFS, e.g. one value per ~50 ms) into claps, for the
 * live "Practice clap level" feedback. A clap is a burst of levels at or above [startDbfs]; it ends
 * when the level falls below [endDbfs], or after [maxTicks] windows (so a continuous noise cannot
 * hold it open). [update] returns the finished clap's peak level, otherwise null.
 *
 * Levels are relative to digital full scale, never SPL.
 */
class ClapPeakTracker(
    private val startDbfs: Double = -30.0,
    private val endDbfs: Double = -40.0,
    private val maxTicks: Int = 10,
) {
    private var peak = Levels.FLOOR_DB
    private var ticks = 0

    fun update(levelDbfs: Double): Double? {
        if (levelDbfs >= startDbfs) {
            peak = max(peak, levelDbfs)
            ticks++
        }
        if (ticks > 0 && (levelDbfs < endDbfs || ticks >= maxTicks)) {
            val finished = peak
            peak = Levels.FLOOR_DB
            ticks = 0
            return finished
        }
        return null
    }
}
