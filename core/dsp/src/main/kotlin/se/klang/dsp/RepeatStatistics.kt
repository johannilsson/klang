package se.klang.dsp

import kotlin.math.abs
import kotlin.math.floor

/**
 * Descriptive statistics of repeated attempts. Deliberately descriptive: with ~5 attempts we
 * report median, interquartile range and the individual values, and make no population-level
 * claims (no confidence percentages).
 *
 * Quartiles use linear interpolation between order statistics (Hyndman & Fan type 7,
 * the default in R and NumPy).
 */
data class RepeatSummary(
    val count: Int,
    val median: Double,
    val q1: Double,
    val q3: Double,
    val min: Double,
    val max: Double,
    val values: List<Double>,
) {
    val iqr: Double get() = q3 - q1
    val range: Double get() = max - min

    companion object {
        fun of(values: Collection<Double>): RepeatSummary? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return RepeatSummary(s.size, quantile(s, 0.5), quantile(s, 0.25), quantile(s, 0.75), s.first(), s.last(), values.toList())
        }

        fun quantile(sorted: List<Double>, q: Double): Double {
            if (sorted.size == 1) return sorted[0]
            val h = (sorted.size - 1) * q
            val lo = floor(h).toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
        }
    }
}

/** Versioned policy for judging repeated attempts within one position/band of one session. */
data class RepeatPolicy(
    val version: String = "0.1.0",
    /** Fewer valid attempts than this: the session cell is reported, but flagged. */
    val minValidAttempts: Int = 3,
    /** An attempt deviating from the cell median by more than this fraction is flagged as an outlier. */
    val outlierFraction: Double = 0.25,
) {
    /** Indices of [values] that deviate from the median by more than [outlierFraction]. Not removed, only flagged. */
    fun outliers(values: List<Double>): Set<Int> {
        if (values.size < minValidAttempts) return emptySet()
        val m = RepeatSummary.of(values)!!.median
        if (m <= 0.0) return emptySet()
        return values.indices.filter { abs(values[it] - m) / m > outlierFraction }.toSet()
    }
}
