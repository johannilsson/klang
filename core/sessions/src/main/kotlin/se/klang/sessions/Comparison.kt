package se.klang.sessions

import kotlinx.serialization.Serializable
import se.klang.dsp.QualityState
import se.klang.dsp.RepeatPolicy
import se.klang.dsp.RepeatSummary
import kotlin.math.abs
import kotlin.math.max

/** One session's repeated T20 attempts at one position and band. */
@Serializable
data class CellSummary(
    val attempted: Int,
    /** Attempts with a T20 value (state VALID or QUESTIONABLE). */
    val usable: Int,
    val valid: Int,
    val questionable: Int,
    val invalid: Int,
    val medianSeconds: Double? = null,
    val q1Seconds: Double? = null,
    val q3Seconds: Double? = null,
    val minSeconds: Double? = null,
    val maxSeconds: Double? = null,
    /** Individual T20 values, in attempt order, with the attempt id. */
    val values: List<AttemptValue> = emptyList(),
    /** Most frequent reasons among invalid attempts, for display. */
    val invalidReasons: List<String> = emptyList(),
) {
    val iqrSeconds: Double? get() = if (q1Seconds != null && q3Seconds != null) q3Seconds - q1Seconds else null
}

@Serializable
data class AttemptValue(val attemptId: String, val t20Seconds: Double, val state: QualityState, val outlier: Boolean)

@Serializable
enum class Verdict {
    /** Treatment median lower by more than the observed variability, and attempt ranges do not overlap. */
    DECREASE_EXCEEDS_VARIABILITY,
    INCREASE_EXCEEDS_VARIABILITY,
    /** "Change is inconclusive". */
    INCONCLUSIVE,
    /** Too few usable attempts in at least one session. */
    INSUFFICIENT_DATA,
}

@Serializable
data class ComparisonCell(
    val positionId: String,
    val positionName: String,
    val bandHz: Int,
    val extendedBand: Boolean,
    val baseline: CellSummary,
    val treatment: CellSummary,
    val repeatBaseline: CellSummary? = null,
    /** treatment − baseline (medians), seconds. Negative = shorter decay after treatment. */
    val changeSeconds: Double? = null,
    /** (baseline − treatment) / baseline × 100, medians; only when the baseline median is valid and > 0. */
    val percentReduction: Double? = null,
    /** The variability the change is judged against; definition in [ComparisonPolicy]. */
    val variabilitySeconds: Double? = null,
    val verdict: Verdict,
    val notes: List<String> = emptyList(),
)

@Serializable
data class ComparisonSummary(
    /** Number of matched (position, band) cells per verdict. No combined score is computed. */
    val cellsByVerdict: Map<Verdict, Int>,
    val totalCells: Int,
    val definition: String,
)

@Serializable
data class Comparison(
    val policyVersion: String,
    val algorithmVersion: String,
    val baselineSessionId: String,
    val treatmentSessionId: String,
    val repeatBaselineSessionId: String? = null,
    /** True if the sessions' capture configurations differ; every cell is then INCONCLUSIVE. */
    val configurationsIncompatible: Boolean,
    val configurationWarnings: List<String>,
    val cells: List<ComparisonCell>,
    val summary: ComparisonSummary,
    /**
     * Sessions in this comparison with impulse attempts that have no analysis at [algorithmVersion].
     * Those attempts count as NOT_ANALYSED; versions are never mixed. Empty when every attempt is covered.
     */
    val staleAnalyses: List<AnalysisCoverage> = emptyList(),
)

/**
 * Which of a session's impulse attempts have an analysis at one algorithm version. Used to prompt a
 * re-analysis (which appends a result) instead of comparing attempts analysed with different versions.
 */
@Serializable
data class AnalysisCoverage(
    val sessionId: String,
    val sessionName: String,
    /** The version coverage was checked against. */
    val algorithmVersion: String,
    val impulseAttempts: Int,
    /** Impulse attempts with no analysis at [algorithmVersion]. */
    val missing: Int,
    /** Among [missing]: the newest version each attempt was analysed with (usually older) → number of attempts. */
    val otherVersions: Map<String, Int> = emptyMap(),
    /** Among [missing]: attempts with no analysis at all. */
    val neverAnalysed: Int = 0,
) {
    companion object {
        fun of(session: Session, attempts: List<CaptureAttempt>, algorithmVersion: String): AnalysisCoverage {
            val impulses = attempts.filter { it.kind == AttemptKind.IMPULSE }
            val missing = impulses.filter { it.analysis(algorithmVersion) == null }
            val other = missing.mapNotNull { it.latestAnalysis()?.algorithmVersion }
                .groupingBy { it }.eachCount().toSortedMap()
            return AnalysisCoverage(
                sessionId = session.id,
                sessionName = session.name,
                algorithmVersion = algorithmVersion,
                impulseAttempts = impulses.size,
                missing = missing.size,
                otherVersions = other,
                neverAnalysed = missing.count { it.analyses.isEmpty() },
            )
        }
    }
}

/**
 * How a before/after change is judged. Versioned; a descriptive rule, not a significance test.
 *
 * For each matched position and band:
 *  - Each session needs at least [RepeatPolicy.minValidAttempts] usable T20 attempts, otherwise
 *    INSUFFICIENT_DATA.
 *  - Observed variability V = max(IQR baseline, IQR treatment, |median baseline − median repeat baseline|).
 *    Without an unchanged-room repeat session, V cannot be established and the result is INCONCLUSIVE.
 *  - The change exceeds variability only if |median treatment − median baseline| > V, and
 *    > [minimumChangeSeconds], and the individual attempt ranges of the two sessions do not overlap.
 *    Otherwise: "Change is inconclusive".
 *  - Incompatible capture configurations make every cell INCONCLUSIVE.
 */
data class ComparisonPolicy(
    val version: String = "0.1.0",
    val repeat: RepeatPolicy = RepeatPolicy(),
    /** Changes smaller than this are not called out regardless of variability. */
    val minimumChangeSeconds: Double = 0.02,
)

class Comparator(private val policy: ComparisonPolicy = ComparisonPolicy()) {

    fun compare(
        room: Room,
        baseline: Session, baselineAttempts: List<CaptureAttempt>,
        treatment: Session, treatmentAttempts: List<CaptureAttempt>,
        repeatBaseline: Session? = null, repeatAttempts: List<CaptureAttempt> = emptyList(),
        algorithmVersion: String,
    ): Comparison {
        val (incompatible, warnings) = configurationWarnings(
            listOfNotNull(baseline to baselineAttempts, treatment to treatmentAttempts, repeatBaseline?.let { it to repeatAttempts }),
            algorithmVersion,
        )
        val bands = (baselineAttempts + treatmentAttempts).flatMap { a -> a.analysis(algorithmVersion)?.bands.orEmpty() }
            .map { it.centerHz to it.extended }.distinct().sortedBy { it.first }
        val cells = ArrayList<ComparisonCell>()
        for (position in room.positions) {
            val b = baselineAttempts.forPosition(position.id)
            val t = treatmentAttempts.forPosition(position.id)
            if (b.isEmpty() || t.isEmpty()) continue // only matched positions
            val r = repeatAttempts.forPosition(position.id)
            for ((band, extended) in bands) {
                cells += cell(position, band, extended, b, t, r.takeIf { repeatBaseline != null }, algorithmVersion, incompatible)
            }
        }
        val byVerdict = Verdict.entries.associateWith { v -> cells.count { it.verdict == v } }
        return Comparison(
            policyVersion = policy.version,
            algorithmVersion = algorithmVersion,
            baselineSessionId = baseline.id,
            treatmentSessionId = treatment.id,
            repeatBaselineSessionId = repeatBaseline?.id,
            configurationsIncompatible = incompatible,
            configurationWarnings = warnings,
            cells = cells,
            summary = ComparisonSummary(
                byVerdict, cells.size,
                "Count of matched (position, octave band) cells by verdict. Bands are not averaged or weighted; " +
                    "cells with missing data are counted as INSUFFICIENT_DATA, not dropped.",
            ),
            staleAnalyses = listOfNotNull(baseline to baselineAttempts, treatment to treatmentAttempts, repeatBaseline?.let { it to repeatAttempts })
                .map { (s, attempts) -> AnalysisCoverage.of(s, attempts, algorithmVersion) }
                .filter { it.missing > 0 },
        )
    }

    fun summarize(attempts: List<CaptureAttempt>, band: Int, algorithmVersion: String): CellSummary {
        val rows = attempts.map { a -> a to a.analysis(algorithmVersion)?.bands?.firstOrNull { it.centerHz == band } }
        val usable = rows.filter { it.second?.t20 != null }
        val values = usable.map { it.second!!.t20!!.seconds }
        val outliers = policy.repeat.outliers(values)
        val stats = RepeatSummary.of(values)
        val invalidReasons = rows.filter { it.second == null || it.second!!.state == QualityState.INVALID }
            .flatMap { (_, b) -> b?.reasons?.filter { it.severity == QualityState.INVALID }?.map { it.code.name } ?: listOf("NOT_ANALYSED") }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { "${it.key} ×${it.value}" }
        return CellSummary(
            attempted = rows.size,
            usable = usable.size,
            valid = rows.count { it.second?.state == QualityState.VALID },
            questionable = rows.count { it.second?.state == QualityState.QUESTIONABLE },
            invalid = rows.count { it.second == null || it.second!!.state == QualityState.INVALID },
            medianSeconds = stats?.median, q1Seconds = stats?.q1, q3Seconds = stats?.q3,
            minSeconds = stats?.min, maxSeconds = stats?.max,
            values = usable.mapIndexed { i, (a, b) -> AttemptValue(a.id, b!!.t20!!.seconds, b.state, i in outliers) },
            invalidReasons = invalidReasons,
        )
    }

    private fun cell(
        position: MeasurementPosition, band: Int, extended: Boolean,
        b: List<CaptureAttempt>, t: List<CaptureAttempt>, r: List<CaptureAttempt>?,
        version: String, incompatible: Boolean,
    ): ComparisonCell {
        val bs = summarize(b, band, version)
        val ts = summarize(t, band, version)
        val rs = r?.let { summarize(it, band, version) }
        val notes = ArrayList<String>()
        if (extended) notes += "Extended band: shown only because attempts passed the same quality gates."
        val bm = bs.medianSeconds
        val tm = ts.medianSeconds
        val change = if (bm != null && tm != null) tm - bm else null
        val pct = if (bm != null && tm != null && bm > 0) (bm - tm) / bm * 100.0 else null
        val min = policy.repeat.minValidAttempts

        var variability: Double? = null
        val verdict = when {
            bs.usable < min || ts.usable < min -> {
                notes += "Needs at least $min usable attempts per session (baseline ${bs.usable}/${bs.attempted}, treatment ${ts.usable}/${ts.attempted})."
                Verdict.INSUFFICIENT_DATA
            }
            incompatible -> {
                notes += "Capture configuration differs between sessions."
                Verdict.INCONCLUSIVE
            }
            rs == null || rs.usable < min || rs.medianSeconds == null -> {
                notes += "No unchanged-room repeat session with enough usable attempts, so normal variability is unknown."
                Verdict.INCONCLUSIVE
            }
            else -> {
                val v = max(max(bs.iqrSeconds!!, ts.iqrSeconds!!), abs(bm!! - rs.medianSeconds))
                variability = v
                val overlap = ts.minSeconds!! <= bs.maxSeconds!! && bs.minSeconds!! <= ts.maxSeconds!!
                val d = change!!
                when {
                    abs(d) <= v || abs(d) < policy.minimumChangeSeconds || overlap -> Verdict.INCONCLUSIVE
                    d < 0 -> Verdict.DECREASE_EXCEEDS_VARIABILITY
                    else -> Verdict.INCREASE_EXCEEDS_VARIABILITY
                }
            }
        }
        if (bs.values.any { it.outlier } || ts.values.any { it.outlier }) notes += "Contains attempts far from the session median (flagged, not removed)."
        if (bs.questionable + ts.questionable > 0) notes += "Includes questionable attempts; inspect their decay curves."
        return ComparisonCell(position.id, position.name, band, extended, bs, ts, rs, change, pct, variability, verdict, notes)
    }

    /** Returns (incompatible, warnings). Only differing configurations are blocking. */
    private fun configurationWarnings(sessions: List<Pair<Session, List<CaptureAttempt>>>, version: String): Pair<Boolean, List<String>> {
        val out = ArrayList<String>()
        var incompatible = false
        val keys = sessions.map { (s, attempts) ->
            s to attempts.mapNotNull { it.audioConfiguration?.compatibilityKey() }.distinct()
        }
        for ((s, k) in keys) {
            if (k.size > 1) { out += "Session '${s.name}' mixes capture configurations."; incompatible = true }
            if (k.isEmpty()) out += "Session '${s.name}' has no recorded capture configuration."
        }
        val distinct = keys.flatMap { it.second }.distinct()
        if (distinct.size > 1) {
            val fields = distinct.first().indices.filter { i -> distinct.map { it[i] }.distinct().size > 1 }
            incompatible = true
            out += "Sessions were captured with different configurations: " + fields.joinToString { i -> distinct.map { it[i] }.distinct().joinToString(" vs ") }
        }
        sessions.flatMap { it.second }.filter { it.kind == AttemptKind.IMPULSE }.forEach { a ->
            if (a.audioConfiguration?.sourceFallback == true) {
                val w = "Some attempts used ${a.audioConfiguration.audioSource} because UNPROCESSED was not available; device processing may be active."
                if (w !in out) out += w
            }
        }
        val missing = sessions.flatMap { it.second }.count { it.kind == AttemptKind.IMPULSE && it.analysis(version) == null }
        if (missing > 0) out += "$missing attempt(s) have no analysis with version $version."
        return incompatible to out
    }

    private fun List<CaptureAttempt>.forPosition(id: String) = filter { it.positionId == id && it.kind == AttemptKind.IMPULSE }
}
