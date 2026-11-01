package se.klang.sessions

import kotlinx.serialization.Serializable
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import se.klang.dsp.RepeatPolicy
import se.klang.dsp.RepeatSummary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/*
 * Plain-language summaries (issue #26): fixed, versioned text rules on top of the existing
 * per-position, per-band results. Deterministic and offline: the same data always gives the same
 * text. Every threshold lives in SummaryPolicy. The texts only describe; they never change a value,
 * a gate or a verdict.
 *
 * Wording rules (tested in SummaryWordingTest): no "significant", "confidence", "certain" or
 * "dB SPL"; no number for an invalid band; EDT never appears; T20 may be called "reverberation" or
 * "how long sound takes to die away".
 */

/**
 * The text rules and their thresholds. Bump [version] on any change to a rule or a threshold;
 * exports record it next to the generated texts.
 */
data class SummaryPolicy(
    val version: String = VERSION,
    /** A band median is only described with at least this many usable claps (same as the comparison's minimum). */
    val minUsableAttempts: Int = RepeatPolicy().minValidAttempts,
    /** High tones "long": mean(2000, 4000 Hz) ≥ this × mean(500, 1000 Hz). */
    val highLongRatio: Double = 1.15,
    /** Mid/low tones "long": mean(250, 500, 1000 Hz) ≥ this × mean(2000, 4000 Hz). */
    val midLowLongRatio: Double = 1.15,
    /** Positions "differ a lot": the longest speech average ≥ this × the shortest. */
    val positionsDifferRatio: Double = 1.25,
    /** Bands "moved most" in a comparison: high bands' mean |change| ≥ this × the mid bands' (or the reverse). */
    val changeShapeRatio: Double = 1.5,
    /**
     * Sabine's constant in s/m, derived rather than typed in: T = 24·ln(10)·V / (c·A), so the
     * constant is 24·ln(10)/c with c the speed of sound (see docs/summaries.md): ≈ 0.161 s/m.
     */
    val sabineConstant: Double = Sabine.constant(Sabine.SPEED_OF_SOUND_METRES_PER_SECOND),
    /** Until the REW comparison (#3) and the repeatability study (#5) are done. */
    val notValidated: Boolean = true,
) {
    companion object {
        const val VERSION = "0.1.0"
    }
}

/** One band at one position, for the summary's bar chart. Invalid bands carry no number. */
@Serializable
data class SummaryBand(
    val bandHz: Int,
    val extended: Boolean,
    /** Median T20 (s) of the usable claps, only with at least [SummaryPolicy.minUsableAttempts]. */
    val medianSeconds: Double? = null,
    val usable: Int,
    val attempted: Int,
    /** Some of the usable claps were questionable (named, value kept). */
    val questionable: Boolean = false,
    /** Why there is no value, in plain words. */
    val missingReason: String? = null,
)

@Serializable
data class TargetSummary(
    val profileId: String,
    val profileVersion: Int,
    /** "your target", "UK school guidance for meeting rooms (BB93, Table 6)", or the declared citation + "imported". */
    val label: String,
    val text: String,
    val evaluation: TargetEvaluation,
)

@Serializable
data class PositionSummary(
    val positionId: String,
    val positionName: String,
    val headline: String,
    /** How high and low tones differ; null when the speech range is not measured. */
    val shape: String? = null,
    /** One line per band without a value. */
    val unmeasured: List<String> = emptyList(),
    val questionable: String? = null,
    val quality: String,
    val advice: String? = null,
    val target: TargetSummary? = null,
    val speechAverageSeconds: Double? = null,
    val bands: List<SummaryBand> = emptyList(),
    /** One sentence for the compact card on the session screen; the fields above are the details. */
    val brief: String = "",
)

/** Plain-language summary of one session. */
@Serializable
data class SessionSummary(
    val policyVersion: String,
    val algorithmVersion: String,
    val sessionId: String,
    val sessionName: String,
    val profileId: String? = null,
    val profileVersion: Int? = null,
    val positions: List<PositionSummary>,
    /** "What usually helps here", room-level. */
    val hints: List<String> = emptyList(),
    /** What claps cannot show, and the validation note. */
    val notes: List<String> = emptyList(),
)

@Serializable
data class PositionChange(
    val positionId: String,
    val positionName: String,
    /** e.g. "Down by more than the normal variation (about 0.16 s, 22 %)" or "Change is inconclusive". */
    val statement: String,
    /** Speech-range verdict of this position, built only from the comparison's cell verdicts. */
    val verdict: Verdict,
    val beforeSeconds: Double? = null,
    val afterSeconds: Double? = null,
    /** Which bands moved, from cells whose verdict exceeds the normal variation only. */
    val where: String? = null,
    /** Per band before and after, for the before/after diagram. A band without usable values carries no number. */
    val bands: List<ChangeBand> = emptyList(),
)

/** One band's medians before and after. A value is given only with enough usable, non-invalid claps. */
@Serializable
data class ChangeBand(
    val bandHz: Int,
    val beforeSeconds: Double? = null,
    val afterSeconds: Double? = null,
    /** The comparison's verdict for this band. */
    val verdict: Verdict,
)

/** Plain-language summary of a comparison. */
@Serializable
data class ComparisonText(
    val policyVersion: String,
    val comparisonPolicyVersion: String,
    val algorithmVersion: String,
    val baselineSessionId: String,
    val treatmentSessionId: String,
    val repeatBaselineSessionId: String? = null,
    val profileId: String? = null,
    val profileVersion: Int? = null,
    val headline: String,
    val variation: String? = null,
    val positions: List<PositionChange>,
    val nextSteps: List<String> = emptyList(),
    val target: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** The built-in suggestions for a room use (target sheet). */
data class TargetSuggestion(val profile: ReferenceProfile, val label: String)

/**
 * Which BB93 room types are suggested for which room use. A product decision (confirmed for #26),
 * not something BB93 says: BB93 sets limits for schools.
 */
object TargetSuggestions {
    fun forUse(use: RoomUse?): List<TargetSuggestion> = when (use) {
        RoomUse.DINING_LIVING -> listOf(
            TargetSuggestion(BuiltInProfiles.BB93_MEETING_ROOM, "relaxed"),
            TargetSuggestion(BuiltInProfiles.BB93_SMALL_GROUP_ROOM, "comfortable for group conversation"),
        )
        RoomUse.HOME_OFFICE -> listOf(
            TargetSuggestion(BuiltInProfiles.BB93_MEETING_ROOM, "like a meeting room"),
            TargetSuggestion(BuiltInProfiles.BB93_OFFICE, "like an office"),
        )
        // BB93's "Kitchen" row (1.5 s) is for school kitchens, a noisy working space: the loosest value, so last.
        RoomUse.KITCHEN -> listOf(
            TargetSuggestion(BuiltInProfiles.BB93_MEETING_ROOM, "relaxed"),
            TargetSuggestion(BuiltInProfiles.BB93_SMALL_GROUP_ROOM, "comfortable for group conversation"),
            TargetSuggestion(BuiltInProfiles.BB93_KITCHEN, "the loosest: a school kitchen at work"),
        )
        RoomUse.OTHER, null -> emptyList()
    }
}

/** How a profile is named in texts. Built-in profiles never as "the standard for your home". */
object ProfileWording {
    fun label(p: ReferenceProfile): String = when (val s = p.source) {
        is ProfileSource.BuiltIn ->
            if (p.id.startsWith("bb93-")) "UK school guidance for ${p.name.lowercase(Locale.ROOT)}s (BB93, Table 6)"
            else "${s.citation.title}, ${s.citation.section}"
        is ProfileSource.User -> "your target"
        is ProfileSource.Imported -> "“${p.name}” from ${s.citation.title}, ${s.citation.section} (imported)"
    }

    /** A sentence on the source and licence, for the target card's footnote. */
    fun footnote(p: ReferenceProfile): String = when (val s = p.source) {
        is ProfileSource.BuiltIn ->
            "From ${s.citation.title}, ${s.citation.edition}, ${s.citation.section}, ${s.citation.licence}. School guidance used as a guide for a home."
        is ProfileSource.User -> "A value you entered."
        is ProfileSource.Imported ->
            "Imported from “${s.fileName}”; it declares ${s.citation.title}${if (s.citation.edition.isNotBlank()) ", ${s.citation.edition}" else ""}, ${s.citation.section}. The app has not checked it."
    }
}

/** Sabine's formula, rearranged for the extra equivalent absorption area. */
object Sabine {
    /** Speed of sound in air at about 20 °C, m/s. */
    const val SPEED_OF_SOUND_METRES_PER_SECOND = 343.0

    /** 60 dB of decay is a factor 10⁶ in energy: T = 6·ln(10)·4V / (c·A) = 24·ln(10)·V / (c·A). */
    fun constant(speedOfSound: Double): Double = 24.0 * kotlin.math.ln(10.0) / speedOfSound

    /**
     * ΔA = c · V · (1/T_target − 1/T_now), m² of fully absorbing surface; null when [nowSeconds] is
     * already at or below [targetSeconds] or an input is not positive.
     */
    fun extraAbsorptionSquareMetres(volumeCubicMetres: Double, nowSeconds: Double, targetSeconds: Double, constant: Double = constant(SPEED_OF_SOUND_METRES_PER_SECOND)): Double? {
        if (volumeCubicMetres <= 0 || nowSeconds <= 0 || targetSeconds <= 0 || nowSeconds <= targetSeconds) return null
        return constant * volumeCubicMetres * (1.0 / targetSeconds - 1.0 / nowSeconds)
    }
}

/**
 * Generates [SessionSummary] and [ComparisonText] from stored results. Pure: no clock, no I/O.
 */
class Summarizer(private val policy: SummaryPolicy = SummaryPolicy()) {
    private val comparator = Comparator()
    private val speech = ProfileLimits.SPEECH_BANDS_HZ

    // ------------------------------------------------------------------------------ sessions

    fun session(
        room: Room, session: Session, attempts: List<CaptureAttempt>, algorithmVersion: String,
        profile: ReferenceProfile? = room.target?.resolve(),
    ): SessionSummary {
        val clean = sanitized(attempts)
        val bandsHz = clean.flatMap { a -> a.analysis(algorithmVersion)?.bands.orEmpty() }
            .map { it.centerHz to it.extended }.distinct().sortedBy { it.first }
            .ifEmpty { ProfileLimits.BANDS_HZ.sorted().map { it to (it == 250 || it == 4000) } }
        val positions = room.positions.mapNotNull { p ->
            val mine = clean.filter { it.positionId == p.id && it.kind == AttemptKind.IMPULSE }
            if (mine.isEmpty()) null else position(p, mine, bandsHz, algorithmVersion, profile, room.volumeCubicMetres)
        }
        return SessionSummary(
            policyVersion = policy.version,
            algorithmVersion = algorithmVersion,
            sessionId = session.id,
            sessionName = session.name,
            profileId = profile?.id,
            profileVersion = profile?.version,
            positions = positions,
            hints = hints(positions, room.volumeCubicMetres, profile),
            notes = notes(),
        )
    }

    private fun position(
        p: MeasurementPosition, attempts: List<CaptureAttempt>, bandsHz: List<Pair<Int, Boolean>>,
        version: String, profile: ReferenceProfile?, volume: Double?,
    ): PositionSummary {
        val bands = bandsHz.map { (hz, ext) ->
            val c = comparator.summarize(attempts, hz, version)
            val enough = c.usable >= policy.minUsableAttempts
            SummaryBand(
                bandHz = hz, extended = ext,
                medianSeconds = c.medianSeconds.takeIf { enough },
                usable = c.usable, attempted = c.attempted,
                questionable = enough && c.values.any { it.state == QualityState.QUESTIONABLE },
                missingReason = if (enough) null else missingReason(c, attempts, hz, version),
            )
        }
        val medians = bands.associate { it.bandHz to it.medianSeconds }
        val tmf = TargetEvaluator.speechAverage(medians)
        val name = p.name

        val headline = if (tmf != null) {
            "After a clap, sound takes about ${s1(tmf)} s to die away in the speech range at $name."
        } else {
            val missing = bands.filter { it.bandHz in speech && it.medianSeconds == null }
            "The speech range could not be measured at $name: " + missing.joinToString("; ") { "${hz(it.bandHz)}: ${it.missingReason}" } + "."
        }
        val shape = tmf?.let { shapeText(shapeOf(medians)) }
        val unmeasured = bands.filter { it.medianSeconds == null && (tmf != null || it.bandHz !in speech) }
            .map { "${hz(it.bandHz)}: not measured (${it.missingReason})." }
        val questionable = bands.filter { it.questionable }.takeIf { it.isNotEmpty() }?.let { q ->
            "Questionable at ${q.joinToString { hz(it.bandHz) }}: the value is kept, but inspect the decay curves."
        }

        // Recording quality: a clap is usable when it has a value in all three speech bands.
        val usableClaps = attempts.count { a ->
            val an = a.analysis(version) ?: return@count false
            speech.all { hz -> an.bands.firstOrNull { it.centerHz == hz }?.t20 != null }
        }
        val failures = attempts.filter { a ->
            val an = a.analysis(version)
            an == null || speech.any { hz -> an.bands.firstOrNull { it.centerHz == hz }?.t20 == null }
        }
        val codes = failures.mapNotNull { a -> mainProblem(a, version) }
        val top = codes.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).firstOrNull()
        val notAnalysed = failures.count { it.analysis(version) == null }
        val quality = buildString {
            append("$usableClaps of ${attempts.size} claps usable in the speech range")
            if (top != null) append("; ${count(top.value, attempts.size)} had ${ReasonTexts.problem(top.key)}")
            if (notAnalysed > 0) append("; $notAnalysed not analysed with the current version")
            append(".")
        }
        val advice = top?.let { ReasonTexts.advice(it.key) }

        val target = profile?.let { prof ->
            val e = TargetEvaluator.evaluate(prof, volume, medians)
            TargetSummary(prof.id, prof.version, ProfileWording.label(prof), targetText(name, prof, e, bands), e)
        }
        val brief = briefLine(name, tmf, target, usableClaps, attempts.size, policy.minUsableAttempts)
        return PositionSummary(p.id, name, headline, shape, unmeasured, questionable, quality, advice, target, tmf, bands, brief)
    }

    /** "Seat A: about 0.7 s in the speech range, 0.13 s above your target. 3 of 5 claps usable." */
    private fun briefLine(name: String, tmf: Double?, target: TargetSummary?, usable: Int, total: Int, needed: Int): String {
        val claps = "$usable of $total claps usable."
        if (tmf == null) {
            // Fewer usable claps than a value needs is not "could not be measured": say how many are missing.
            return if (usable in 1 until needed) "$name: $usable of $total claps usable so far; $needed are needed for a value."
            else "$name: the speech range could not be measured. $claps"
        }
        val item = target?.evaluation?.items?.firstOrNull { it.bandHz == null && it.status != null }
        val against = if (item == null) "" else {
            val goal = if (target.label == "your target") "your target" else "the target"
            when (item.status) {
                TargetStatus.ABOVE -> ", ${s2(item.outsideBySeconds!!)} s above $goal"
                TargetStatus.BELOW -> ", ${s2(item.outsideBySeconds!!)} s below the range of $goal"
                else -> ", within $goal"
            }
        }
        return "$name: about ${s1(tmf)} s in the speech range$against. $claps"
    }

    private fun targetText(name: String, p: ReferenceProfile, e: TargetEvaluation, bands: List<SummaryBand>): String {
        val label = ProfileWording.label(p)
        e.reason?.let { r ->
            return when (r) {
                NotEvaluableReason.VOLUME_REQUIRED -> "This target depends on the room volume. Add the volume under Room › Target to compare $name with it."
                NotEvaluableReason.VOLUME_OUT_OF_RANGE -> "The room volume is outside the range this target is defined for, so $name can't be compared with it."
                NotEvaluableReason.BAND_MISSING -> "$name can't be compared with $label."
            }
        }
        val parts = e.items.map { item ->
            if (item.status == null) {
                val why = item.missingBandsHz.joinToString("; ") { hz -> "${hz(hz)}: ${bands.firstOrNull { it.bandHz == hz }?.missingReason ?: "no usable value"}" }
                "$name can't be compared with $label: $why."
            } else {
                val what = if (item.bandHz == null) "Speech-range average at $name: ${s2(item.measuredSeconds!!)} s (500, 1000 and 2000 Hz)"
                else "${hz(item.bandHz)} at $name: ${s2(item.measuredSeconds!!)} s"
                "$what. ${targetPhrase(p, label, item)}"
            }
        }
        return (parts + "A target for a whole room compared with one position is a guide, not a pass or fail.").joinToString(" ")
    }

    private fun targetPhrase(p: ReferenceProfile, label: String, item: TargetItem): String {
        val cap = label.replaceFirstChar { it.uppercase() }
        val m = item.measuredSeconds!!
        val upper = item.upperSeconds!!
        return when (p.target) {
            is ReverbTarget.UpperLimit -> {
                val lim = "${cap.let { if (p.source is ProfileSource.User) "Your target" else it }} is at most ${s2(upper)} s"
                when {
                    item.status == TargetStatus.ABOVE -> "$lim, so it is ${s2(m - upper)} s above it."
                    s2(m) == s2(upper) -> "$lim, so it is at the target."
                    else -> "$lim, so it is ${s2(upper - m)} s below it."
                }
            }
            else -> {
                val range = "${item.lowerSeconds?.let { s2(it) + "–" } ?: "up to "}${s2(upper)} s"
                val lead = "${if (p.source is ProfileSource.User) "Your target" else cap} is $range"
                when (item.status) {
                    TargetStatus.ABOVE -> "$lead, so it is ${s2(item.outsideBySeconds!!)} s above that range."
                    TargetStatus.BELOW -> "$lead, so it is ${s2(item.outsideBySeconds!!)} s below that range."
                    else -> "$lead, so it is within it."
                }
            }
        }
    }

    private enum class Shape { HIGH_LONG, MID_LOW_LONG, EVEN }

    private fun shapeOf(medians: Map<Int, Double?>): Shape {
        val mid = mean(listOf(500, 1000).mapNotNull { medians[it] })
        val high = mean(listOf(2000, 4000).mapNotNull { medians[it] })
        val midLow = mean(listOf(250, 500, 1000).mapNotNull { medians[it] })
        return when {
            mid != null && high != null && high >= policy.highLongRatio * mid -> Shape.HIGH_LONG
            midLow != null && high != null && midLow >= policy.midLowLongRatio * high -> Shape.MID_LOW_LONG
            else -> Shape.EVEN
        }
    }

    private fun shapeText(s: Shape) = when (s) {
        Shape.HIGH_LONG -> "It is longest in the high tones (2000–4000 Hz)."
        Shape.MID_LOW_LONG -> "It is longest in the mid and low tones and shorter in the high tones."
        Shape.EVEN -> "It is about the same across the measured bands."
    }

    private fun hints(positions: List<PositionSummary>, volume: Double?, profile: ReferenceProfile?): List<String> {
        val measured = positions.filter { it.speechAverageSeconds != null }
        if (measured.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val evaluated = measured.mapNotNull { it.target?.evaluation?.items?.firstOrNull { i -> i.bandHz == null && i.status != null } }
        val allWithin = profile != null && evaluated.size == measured.size && evaluated.all { it.status == TargetStatus.WITHIN }
        val lowMissing = positions.all { ps -> ps.bands.none { it.bandHz == 250 && it.medianSeconds != null } } &&
            positions.any { ps -> ps.bands.any { it.bandHz == 250 } }
        if (allWithin) {
            out += "Every measured position is at or below the target on average, so the target does not call for a change."
            out += "If you still hear a ringing echo, it may be a flutter echo between bare parallel walls, which these claps can't show. " +
                "Soft furnishings or absorbers on one of the two walls usually help."
        } else {
            // Room-level band shape: median across positions of each band's median.
            val roomMedians = (ProfileLimits.BANDS_HZ).associateWith { hz ->
                positions.mapNotNull { ps -> ps.bands.firstOrNull { it.bandHz == hz }?.medianSeconds }.takeIf { it.isNotEmpty() }?.let { median(it) }
            }
            val aboveTarget = evaluated.any { it.status == TargetStatus.ABOVE }
            when (shapeOf(roomMedians)) {
                Shape.HIGH_LONG -> out += "The high tones ring longest. Soft furnishings usually help most there: curtains, a rug, upholstered chairs, fabric wall hangings."
                Shape.MID_LOW_LONG -> out += "The mid and low tones ring longest. Thin materials barely reach them; thick porous absorbers usually do: panels 5–10 cm deep (more with an air gap behind), a sofa, full bookshelves."
                Shape.EVEN -> if (aboveTarget) out += "The decay is about even across the bands. More absorbing area in general usually shortens all of them."
            }
            if (measured.size >= 2) {
                val longest = measured.maxBy { it.speechAverageSeconds!! }
                val shortest = measured.minBy { it.speechAverageSeconds!! }
                if (longest.speechAverageSeconds!! >= policy.positionsDifferRatio * shortest.speechAverageSeconds!!) {
                    out += "${longest.positionName} rings longest of the measured positions. Start near it."
                }
            }
            val limit = profile?.let { evaluated.firstOrNull()?.upperSeconds }
            val now = mean(measured.mapNotNull { it.speechAverageSeconds })
            if (volume != null && limit != null && now != null && aboveTarget) {
                Sabine.extraAbsorptionSquareMetres(volume, now, limit, policy.sabineConstant)?.let { a ->
                    out += "For this ${s0(volume)} m³ room, roughly ${area(a)} m² more of fully absorbing surface would bring the average speech-range " +
                        "reverberation of the measured positions to ${s2(limit)} s (Sabine estimate). Real materials absorb only part of the sound, so more of them is needed."
                }
            }
        }
        val below = measured.filter { ps -> ps.target?.evaluation?.items?.any { it.bandHz == null && it.status == TargetStatus.BELOW } == true }
        if (below.isNotEmpty() && evaluated.none { it.status == TargetStatus.ABOVE }) {
            out += "${below.joinToString { it.positionName }} ${if (below.size == 1) "is" else "are"} already shorter than the range of the target; " +
                "more absorption there would make it drier still."
        }
        if (lowMissing) out += "Low tones (250 Hz) can't be judged from these claps."
        return out
    }

    private fun notes() = buildList {
        add("Claps can't show everything: a fluttering echo between two bare parallel walls won't appear here. Clap once and listen.")
        if (policy.notValidated) add(NOT_VALIDATED)
    }

    // ---------------------------------------------------------------------------- comparisons

    fun comparison(
        c: Comparison, room: Room, sessions: List<Session>,
        profile: ReferenceProfile? = room.target?.resolve(),
    ): ComparisonText {
        val byId = sessions.associateBy { it.id }
        val treatment = byId[c.treatmentSessionId]
        val intervention = treatment?.intervention.orEmpty()
        val positions = c.cells.groupBy { it.positionId }.map { (_, cells) -> positionChange(cells, intervention) }
        val down = positions.count { it.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY }
        val up = positions.count { it.verdict == Verdict.INCREASE_EXCEEDS_VARIABILITY }
        val n = positions.size
        val after = treatment?.let { "After “${it.intervention.ifBlank { it.name }}”" } ?: "After the change"
        val headline = when {
            n == 0 -> "No matched positions to compare yet: both sessions need claps at the same position."
            down > 0 && up == 0 -> "$after, reverberation in the speech range went down by more than the normal variation at $down of $n position${if (n == 1) "" else "s"}."
            up > 0 && down == 0 -> "$after, reverberation in the speech range went up by more than the normal variation at $up of $n position${if (n == 1) "" else "s"}."
            down > 0 && up > 0 -> "$after, reverberation in the speech range went down at $down and up at $up of $n positions, each by more than the normal variation."
            positions.all { it.verdict == Verdict.INSUFFICIENT_DATA } -> "Not enough usable claps to compare the speech range yet."
            positions.none { it.verdict == Verdict.INSUFFICIENT_DATA } -> "Change is inconclusive at every position."
            else -> "Change is inconclusive at the positions that could be compared; the others need more usable claps."
        }
        val vs = c.cells.filter { it.bandHz in speech }.mapNotNull { it.variabilitySeconds }
        val variation = vs.maxOrNull()?.let {
            "Normal variation here is up to about ${s2(it)} s per band: the larger of the spread of the claps and the difference between your two unchanged-room baselines."
        }
        val steps = ArrayList<String>()
        if (c.configurationsIncompatible) {
            steps += "The sessions were recorded with different capture settings, so every change is inconclusive: " +
                c.configurationWarnings.joinToString(" ") { w -> if (w.endsWith(".")) w else "$w." } + " Record them again with the same phone and settings."
        }
        if (c.repeatBaselineSessionId == null) {
            steps += "Record a repeat baseline with the room unchanged. Without it the normal variation is unknown, so every change is inconclusive."
        } else if (c.cells.any { it.verdict != Verdict.INSUFFICIENT_DATA && it.repeatBaseline != null && it.repeatBaseline.usable < RepeatPolicy().minValidAttempts }) {
            steps += "The repeat baseline has too few usable claps at some positions; record more there."
        }
        positions.filter { it.verdict == Verdict.INSUFFICIENT_DATA }.forEach {
            steps += "Record more claps at ${it.positionName}: at least ${RepeatPolicy().minValidAttempts} usable ones in each session."
        }
        if (c.staleAnalyses.isNotEmpty()) steps += "Some recordings were analysed with an older version; re-analyse them to include them."

        val targetLines = profile?.let { p ->
            positions.mapNotNull { pc ->
                val medians = c.cells.filter { it.positionId == pc.positionId }.associate { cell ->
                    cell.bandHz to cell.treatment.medianSeconds.takeIf { cell.treatment.usable >= policy.minUsableAttempts }
                }
                val item = TargetEvaluator.evaluate(p, room.volumeCubicMetres, medians).items.singleOrNull { it.bandHz == null && it.status != null }
                    ?: return@mapNotNull null
                val lim = s2(item.upperSeconds!!)
                val label = if (p.source is ProfileSource.User) "your target of $lim s" else "${ProfileWording.label(p)}, at most $lim s"
                when (item.status) {
                    TargetStatus.ABOVE -> "${pc.positionName} is now about ${s2(item.measuredSeconds!!)} s, above $label."
                    else -> "${pc.positionName} is now about ${s2(item.measuredSeconds!!)} s, ${if (s2(item.measuredSeconds!!) == lim) "at" else "within"} $label."
                }
            }
        }.orEmpty()

        return ComparisonText(
            policyVersion = policy.version,
            comparisonPolicyVersion = c.policyVersion,
            algorithmVersion = c.algorithmVersion,
            baselineSessionId = c.baselineSessionId,
            treatmentSessionId = c.treatmentSessionId,
            repeatBaselineSessionId = c.repeatBaselineSessionId,
            profileId = profile?.id,
            profileVersion = profile?.version,
            headline = headline,
            variation = variation,
            positions = positions,
            nextSteps = steps,
            target = targetLines,
            notes = listOfNotNull(
                "“More than the normal variation” is a descriptive rule, not a statistical test.",
                NOT_VALIDATED.takeIf { policy.notValidated },
            ),
        )
    }

    private fun positionChange(cells: List<ComparisonCell>, intervention: String): PositionChange {
        val first = cells.first()
        val sp = cells.filter { it.bandHz in speech }
        val verdicts = sp.map { it.verdict }
        val verdict = when {
            sp.size < speech.size || Verdict.INSUFFICIENT_DATA in verdicts -> Verdict.INSUFFICIENT_DATA
            verdicts.all { it == Verdict.DECREASE_EXCEEDS_VARIABILITY } -> Verdict.DECREASE_EXCEEDS_VARIABILITY
            verdicts.all { it == Verdict.INCREASE_EXCEEDS_VARIABILITY } -> Verdict.INCREASE_EXCEEDS_VARIABILITY
            else -> Verdict.INCONCLUSIVE
        }
        val before = if (verdict == Verdict.INSUFFICIENT_DATA) null else TargetEvaluator.speechAverage(sp.associate { it.bandHz to it.baseline.medianSeconds })
        val after = if (verdict == Verdict.INSUFFICIENT_DATA) null else TargetEvaluator.speechAverage(sp.associate { it.bandHz to it.treatment.medianSeconds })
        val size = if (before != null && after != null && before > 0) " (about ${s2(abs(before - after))} s, ${s0(abs(before - after) / before * 100)} %)" else ""
        val moved = sp.filter { it.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY || it.verdict == Verdict.INCREASE_EXCEEDS_VARIABILITY }
        val statement = when (verdict) {
            Verdict.DECREASE_EXCEEDS_VARIABILITY -> "Down by more than the normal variation$size."
            Verdict.INCREASE_EXCEEDS_VARIABILITY -> "Up by more than the normal variation$size."
            Verdict.INSUFFICIENT_DATA -> "Not enough usable claps to compare the speech range."
            Verdict.INCONCLUSIVE ->
                if (moved.isEmpty()) "Change is inconclusive."
                else "Change is inconclusive for the speech range as a whole; " +
                    moved.joinToString(" and ") { "${hz(it.bandHz)} went ${if (it.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY) "down" else "up"}" } +
                    " by more than the normal variation."
        }
        return PositionChange(first.positionId, first.positionName, statement, verdict, before, after, where(cells, intervention), changeBands(cells))
    }

    /** Per band medians before and after; claps in an invalid band never count, even with a stale stored fit. */
    private fun changeBands(cells: List<ComparisonCell>): List<ChangeBand> = cells.sortedBy { it.bandHz }.map { c ->
        fun median(x: CellSummary): Double? =
            x.values.filter { it.state != QualityState.INVALID }.map { it.t20Seconds }
                .takeIf { it.size >= policy.minUsableAttempts }?.let { RepeatSummary.of(it)?.median }
        ChangeBand(c.bandHz, median(c.baseline), median(c.treatment), c.verdict)
    }

    /** Which bands moved most, from cells whose verdict exceeds the normal variation only. */
    private fun where(cells: List<ComparisonCell>, intervention: String): String? {
        val exceeded = cells.filter { it.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY || it.verdict == Verdict.INCREASE_EXCEEDS_VARIABILITY }
        if (exceeded.isEmpty()) return null
        val list = exceeded.sortedByDescending { abs(it.changeSeconds ?: 0.0) }.joinToString { hz(it.bandHz) }
        val mid = mean(cells.filter { it.bandHz in setOf(500, 1000) && it.verdict != Verdict.INSUFFICIENT_DATA }.mapNotNull { it.changeSeconds?.let(::abs) })
        val high = mean(cells.filter { it.bandHz in setOf(2000, 4000) && it.verdict != Verdict.INSUFFICIENT_DATA }.mapNotNull { it.changeSeconds?.let(::abs) })
        val highMoved = exceeded.any { it.bandHz >= 2000 }
        val midMoved = exceeded.any { it.bandHz in setOf(250, 500, 1000) }
        val text = StringBuilder("Changed by more than the normal variation at $list (largest first).")
        val soft = SOFT.containsMatchIn(intervention)
        val thick = THICK.containsMatchIn(intervention)
        when {
            mid != null && high != null && highMoved && high >= policy.changeShapeRatio * mid -> {
                text.append(" The biggest change is in the high tones")
                text.append(if (soft) ", which is typical for soft furnishings such as curtains and rugs." else ".")
            }
            mid != null && high != null && midMoved && mid >= policy.changeShapeRatio * high -> {
                text.append(" The biggest change is in the mid tones")
                text.append(if (thick) ", which is typical for thick absorbers." else ".")
            }
        }
        return text.toString()
    }

    // --------------------------------------------------------------------------------- helpers

    /** Invalid bands never carry a value, even if a stored record has a stale fit. */
    private fun sanitized(attempts: List<CaptureAttempt>) = attempts.map { a ->
        a.copy(analyses = a.analyses.map { an -> an.copy(bands = an.bands.map { b -> if (b.state == QualityState.INVALID) b.copy(t20 = null) else b }) })
    }

    private fun missingReason(c: CellSummary, attempts: List<CaptureAttempt>, hz: Int, version: String): String {
        if (c.usable > 0) return "only ${c.usable} usable clap${if (c.usable == 1) "" else "s"}, needs ${policy.minUsableAttempts}"
        val codes = attempts.mapNotNull { a ->
            val b = a.analysis(version)?.bands?.firstOrNull { it.centerHz == hz } ?: return@mapNotNull null
            b.reasons.filter { it.severity == QualityState.INVALID }.minByOrNull { it.code.ordinal }?.code
        }
        val top = codes.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).firstOrNull()?.key
            ?: return "not analysed"
        return ReasonTexts.bandProblem(top)
    }

    /** The attempt-level invalid reason, else the first speech band's invalid reason. */
    private fun mainProblem(a: CaptureAttempt, version: String): ReasonCode? {
        val an = a.analysis(version) ?: return null
        an.reasons.filter { it.severity == QualityState.INVALID }.minByOrNull { it.code.ordinal }?.let { return it.code }
        return speech.firstNotNullOfOrNull { hz ->
            an.bands.firstOrNull { it.centerHz == hz }?.takeIf { it.t20 == null }?.reasons?.filter { it.severity == QualityState.INVALID }?.minByOrNull { it.code.ordinal }?.code
        }
    }

    private fun count(n: Int, of: Int): String = when {
        n == of && of > 1 -> "all"
        n in 1..WORDS.lastIndex -> WORDS[n]
        else -> n.toString()
    }

    private fun mean(v: List<Double>) = if (v.isEmpty()) null else v.sum() / v.size
    private fun median(v: List<Double>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun hz(v: Int) = "$v Hz"
    private fun s0(v: Double) = String.format(Locale.ROOT, "%.0f", v)
    private fun s1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun s2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    /** Rounded to 0.5 m² below 10 m², to 1 m² above: the estimate is rough. */
    private fun area(a: Double) = if (a < 10) String.format(Locale.ROOT, "%.1f", (round(a * 2) / 2).coerceAtLeast(0.5)).removeSuffix(".0") else s0(a)

    companion object {
        const val NOT_VALIDATED = "Not yet checked against reference equipment. Use it to compare before and after, not as an exact value."
        private val WORDS = listOf("none", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        private val SOFT = Regex("(?i)curtain|drape|rug|carpet|blanket|duvet|cushion|textile|fabric|upholster")
        private val THICK = Regex("(?i)panel|absorber|bookshel|sofa|mineral wool|foam")
    }
}
