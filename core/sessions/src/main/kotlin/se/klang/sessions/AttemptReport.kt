package se.klang.sessions

import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import java.util.Locale

/*
 * What to tell the user about one recording: a verdict, a few facts, and each distinct problem once.
 *
 * The analysis gives reasons per band, and the same cause (a noisy room) shows up as several reasons in
 * every band. This groups them by cause so a problem is stated once with its advice and the bands it
 * affects, instead of once per band. Deterministic text rules, like the summaries; nothing here changes an
 * analysis, a gate or a verdict of the comparison.
 *
 * Wording (tested): no "significant", "confidence", "dB SPL" or EDT, and no number for an invalid band.
 */

enum class AttemptVerdict { USABLE, USABLE_WITH_CAUTION, NOT_USABLE, NOT_ANALYSED }

enum class FindingKind { PROBLEM, CAUTION, OK }

/** One thing worth saying about a recording. [short] is the one-line form for lists. */
data class Finding(
    val kind: FindingKind,
    val title: String,
    val detail: String,
    val short: String,
    val advice: String? = null,
    /** The bands this is about, empty when it is about the whole recording. */
    val bandsHz: List<Int> = emptyList(),
)

/** A fact shown as a chip, e.g. "Clap −6.0 dBFS · good level". [warn] marks the one that is wrong. */
data class ReportChip(val text: String, val warn: Boolean = false)

data class AttemptReport(
    val verdict: AttemptVerdict,
    val headline: String,
    val summary: String,
    val chips: List<ReportChip>,
    /** Problems first, then cautions, then what was fine. */
    val findings: List<Finding>,
    /** One line for a list row: the reason it is not usable, the caution, or the values. */
    val oneLine: String,
    /** "500 Hz 0.78 · 1k 0.74 · 2k 0.66 · 4k 0.59 s" for the bands that have a value, else null. */
    val t20Line: String?,
)

data class AttemptReportPolicy(
    /** A clap peak in this range, dBFS (not SPL), is a good level; above it the microphone may compress. */
    val goodClapMinDbfs: Double = GOOD_CLAP_MIN_DBFS,
    val goodClapMaxDbfs: Double = GOOD_CLAP_MAX_DBFS,
    /** A background at or below this is called quiet. */
    val quietBackgroundMaxDbfs: Double = -55.0,
    /** The bands a recording needs a value in to count as usable (the speech range). */
    val speechBandsHz: List<Int> = ProfileLimits.SPEECH_BANDS_HZ,
) {
    companion object {
        /** Same range as the live level meter and the practice mode. */
        const val GOOD_CLAP_MAX_DBFS = -2.0
        const val GOOD_CLAP_MIN_DBFS = -12.0
    }
}

object AttemptReporter {
    /** The measured background this far above the typical quiet window means a sound raised it. */
    private const val RAISED_BACKGROUND_DB = 10.0

    private enum class Group { NOISE, OTHER_SOUND, TOO_LOUD, TIMING, DISTURBED, DECAY, OTHER }

    private fun groupOf(c: ReasonCode) = when (c) {
        ReasonCode.INSUFFICIENT_SNR_MARGIN, ReasonCode.INSUFFICIENT_DECAY_RANGE -> Group.NOISE
        ReasonCode.ADDITIONAL_IMPULSE, ReasonCode.BACKGROUND_TRANSIENT -> Group.OTHER_SOUND
        ReasonCode.CLIPPING, ReasonCode.FLAT_TOP, ReasonCode.FULL_SCALE_SAMPLE, ReasonCode.NEAR_FULL_SCALE -> Group.TOO_LOUD
        ReasonCode.INSUFFICIENT_PRE_ROLL, ReasonCode.INSUFFICIENT_TAIL, ReasonCode.NO_IMPULSE_FOUND -> Group.TIMING
        ReasonCode.CAPTURE_DISCONTINUITY, ReasonCode.DROPOUT -> Group.DISTURBED
        ReasonCode.NON_LINEAR_DECAY, ReasonCode.CURVED_DECAY, ReasonCode.TRUNCATION_NOT_CONVERGED, ReasonCode.TAIL_NOISE_MISMATCH,
        ReasonCode.FILTER_RINGING_LIMIT, ReasonCode.NON_PHYSICAL_SLOPE, ReasonCode.DECAY_OUT_OF_RANGE -> Group.DECAY
        else -> Group.OTHER
    }

    private class Info {
        val codes = LinkedHashSet<ReasonCode>()
        val bands = sortedSetOf<Int>()
        var wholeRecording = false
        var invalid = false
        var questionable = false
        var firstMessage = ""
        var threshold: Double? = null
    }

    fun report(
        analysis: AnalysisRecord?,
        captureEvents: List<CaptureEvent> = emptyList(),
        timeline: RecordingTimeline? = null,
        policy: AttemptReportPolicy = AttemptReportPolicy(),
    ): AttemptReport {
        if (analysis == null) {
            return AttemptReport(
                AttemptVerdict.NOT_ANALYSED, "Not analysed yet", "This recording has no analysis with the current algorithm version.",
                emptyList(), emptyList(), "Not analysed yet.", null,
            )
        }
        val allBands = analysis.bands.map { it.centerHz }
        val hasValue = { b: BandRecord -> b.state != QualityState.INVALID && b.t20 != null }
        val speech = analysis.bands.filter { it.centerHz in policy.speechBandsHz }
        val speechOk = speech.size == policy.speechBandsHz.size && speech.all(hasValue)

        // Group every reason by cause.
        val groups = LinkedHashMap<Group, Info>()
        fun add(r: Reason, band: Int?) {
            if (r.severity == QualityState.VALID) return
            val info = groups.getOrPut(groupOf(r.code)) { Info() }
            info.codes += r.code
            if (band == null) info.wholeRecording = true else info.bands += band
            if (r.severity == QualityState.INVALID) info.invalid = true else info.questionable = true
            if (info.firstMessage.isEmpty()) info.firstMessage = r.message
            if (r.threshold != null && r.code == ReasonCode.INSUFFICIENT_SNR_MARGIN) info.threshold = r.threshold
        }
        analysis.reasons.forEach { add(it, null) }
        analysis.bands.forEach { b -> b.reasons.forEach { add(it, b.centerHz) } }

        val problems = ArrayList<Finding>()
        val cautions = ArrayList<Finding>()
        var cautionRelevant = false
        val beforeClap = timeline?.events?.firstOrNull { it.kind == SoundEvent.Kind.BEFORE_CLAP }
        // A loud sound in the quiet part raises the background the bands are judged against, so they all fail the
        // noise check because of it, not because of the room. Then the sound is the problem, and the room was quiet.
        val quiet = timeline?.quietDbfs
        val raisedBackground = groups[Group.OTHER_SOUND]?.invalid == true && beforeClap != null && quiet != null &&
            (analysis.backgroundRmsDbfs ?: Double.NEGATIVE_INFINITY) - quiet >= RAISED_BACKGROUND_DB && groups[Group.NOISE] != null
        for ((group, info) in groups) {
            if (group == Group.NOISE && raisedBackground) continue
            val everyBand = info.wholeRecording || info.bands.containsAll(allBands)
            val relevant = info.wholeRecording || info.bands.any { it in policy.speechBandsHz }
            val kind = if (info.invalid && relevant) FindingKind.PROBLEM else FindingKind.CAUTION
            val f = finding(group, info, kind, everyBand, analysis, beforeClap, policy, raisedBackground)
            if (kind == FindingKind.PROBLEM) problems += f else {
                cautions += f
                if (relevant && info.questionable && !info.invalid) cautionRelevant = true
            }
        }
        if (!speechOk && problems.isEmpty()) {
            problems += Finding(
                FindingKind.PROBLEM, "The speech range could not be measured.", "500, 1000 and 2000 Hz all need a value for a clap to count.",
                "The speech range could not be measured.",
            )
        }

        val verdict = when {
            !speechOk -> AttemptVerdict.NOT_USABLE
            cautionRelevant -> AttemptVerdict.USABLE_WITH_CAUTION
            else -> AttemptVerdict.USABLE
        }

        // What was fine.
        val ok = ArrayList<Finding>()
        val peak = analysis.peakDbfs
        val loud = groups[Group.TOO_LOUD]
        if (loud == null && peak >= policy.goodClapMinDbfs && peak < policy.goodClapMaxDbfs) {
            ok += Finding(FindingKind.OK, "Clap level is good.", "${db(peak)} dBFS, with headroom before the microphone clips.", "Good clap level.")
        } else if (loud == null && peak < policy.goodClapMinDbfs && groups[Group.NOISE] != null && !raisedBackground) {
            cautions += Finding(
                FindingKind.CAUTION, "The clap was quiet (${db(peak)} dBFS).", "A little louder or closer leaves more room above the noise.",
                "Quiet clap (${db(peak)} dBFS).", "Step closer or clap a little firmer.",
            )
        }
        if (groups[Group.OTHER_SOUND] == null && beforeClap == null) {
            ok += Finding(
                FindingKind.OK, "Nothing else was louder than the room.", "No tap, movement or second clap before or during the decay.",
                "No other sounds.",
            )
        }
        if (groups[Group.DISTURBED] == null && captureEvents.isEmpty()) {
            ok += Finding(FindingKind.OK, "Clean recording.", "No dropouts, route changes or other capture events.", "Clean recording.")
        }
        timeline?.events?.filter { it.kind == SoundEvent.Kind.AFTER_DECAY }?.forEach { e ->
            ok += Finding(
                FindingKind.OK, "The later sound at ${sec(e.timeSeconds)} s does not matter.",
                "The clap had already faded into the background, so it cannot change any band's result.", "A later sound does not matter.",
            )
        }

        val noiseHigh = groups[Group.NOISE]?.let { it.invalid && (it.wholeRecording || it.bands.any { b -> b in policy.speechBandsHz }) } == true
        val chips = ArrayList<ReportChip>()
        if (beforeClap != null && groups[Group.OTHER_SOUND] != null) {
            chips += ReportChip("Other sound at ${sec(beforeClap.timeSeconds)} s · ${db(beforeClap.levelDbfs)} dBFS", warn = true)
        }
        chips += when {
            peak >= policy.goodClapMaxDbfs -> ReportChip("Clap ${db(peak)} dBFS · very loud", warn = true)
            // Quiet is only a problem when the noise check failed; in a very quiet room a quiet clap measures fine.
            peak < policy.goodClapMinDbfs -> if (groups[Group.NOISE] != null && !raisedBackground) ReportChip("Clap ${db(peak)} dBFS · quiet", warn = true) else ReportChip("Clap ${db(peak)} dBFS")
            else -> ReportChip("Clap ${db(peak)} dBFS · good level")
        }
        (if (raisedBackground) quiet else analysis.backgroundRmsDbfs)?.let { bg ->
            chips += when {
                noiseHigh && !raisedBackground -> ReportChip("Background ${db(bg)} dBFS · too high", warn = true)
                bg <= policy.quietBackgroundMaxDbfs -> ReportChip("Background ${db(bg)} dBFS · quiet")
                else -> ReportChip("Background ${db(bg)} dBFS")
            }
        }
        analysis.onsetSeconds?.let { chips += ReportChip("Clap at ${sec(it)} s") }

        val t20Line = analysis.bands.filter(hasValue).takeIf { it.isNotEmpty() }?.let { bs ->
            bs.mapIndexed { i, b -> "${bandLabel(b.centerHz, first = i == 0)} ${String.format(Locale.ROOT, "%.2f", b.t20!!.seconds)}" }
                .joinToString(" · ") + " s"
        }
        val measured = analysis.bands.count(hasValue)

        val (headline, summary) = when (verdict) {
            AttemptVerdict.USABLE ->
                "Usable clap" to if (measured == allBands.size) "All ${words(measured)} bands could be measured." else "${cap(words(measured))} of ${words(allBands.size)} bands could be measured."
            AttemptVerdict.USABLE_WITH_CAUTION ->
                "Usable with caution" to "It can be used. Note: ${cautions.first { it.kind == FindingKind.CAUTION }.title}"
            AttemptVerdict.NOT_USABLE -> notUsable(problems.first(), groups.filterKeys { !(raisedBackground && it == Group.NOISE) }, measured, allBands.size, beforeClap, peak, policy)
            AttemptVerdict.NOT_ANALYSED -> "Not analysed yet" to ""
        }
        val relevantCaution = cautions.firstOrNull { it.kind == FindingKind.CAUTION }
        val oneLine = when (verdict) {
            AttemptVerdict.NOT_USABLE -> problems.first().short
            AttemptVerdict.USABLE_WITH_CAUTION -> listOfNotNull(relevantCaution?.short, t20Line).joinToString(" ")
            AttemptVerdict.USABLE -> t20Line ?: "Usable."
            AttemptVerdict.NOT_ANALYSED -> "Not analysed yet."
        }
        return AttemptReport(verdict, headline, summary, chips, problems + cautions + ok, oneLine, t20Line)
    }

    private fun notUsable(
        main: Finding, groups: Map<Group, Info>, measured: Int, total: Int, beforeClap: SoundEvent?, peak: Double, policy: AttemptReportPolicy,
    ): Pair<String, String> {
        val clapFine = peak >= policy.goodClapMinDbfs && peak < policy.goodClapMaxDbfs
        val problemGroup = groups.entries.firstOrNull { it.value.invalid }?.key
        return when (problemGroup) {
            Group.NOISE -> {
                val info = groups.getValue(Group.NOISE)
                val every = info.wholeRecording || info.bands.size >= total
                val lead = if (clapFine) "The clap was loud and clear, but the background noise" else "The background noise"
                "Not usable: the room was too noisy" to
                    if (every) "$lead was too close to it in every band, so no band could be measured."
                    else "$lead was too close to it in some bands, so the speech range could not be measured."
            }
            Group.OTHER_SOUND ->
                (if (beforeClap != null) "Not usable: a sound came before the clap" else "Not usable: another sound disturbed it") to
                    "A tap or movement${beforeClap?.let { " at ${sec(it.timeSeconds)} s" } ?: ""} was too loud to ignore, so the whole recording was rejected."
            Group.TOO_LOUD -> "Not usable: the clap was too loud" to "The clap reached full scale, so the microphone distorted it."
            Group.TIMING -> "Not usable: ${main.short.trimEnd('.').replaceFirstChar { it.lowercase() }}" to main.detail
            Group.DISTURBED -> "Not usable: the recording was disturbed" to main.detail
            Group.DECAY -> "Not usable: the decay could not be measured" to main.detail
            else -> "Not usable" to (if (measured == 0) "No band could be measured." else main.detail)
        }
    }

    private fun finding(
        group: Group, info: Info, kind: FindingKind, everyBand: Boolean, analysis: AnalysisRecord, beforeClap: SoundEvent?, policy: AttemptReportPolicy,
        raisedBackground: Boolean,
    ): Finding {
        val bands = if (info.wholeRecording) emptyList() else info.bands.toList()
        val caution = kind == FindingKind.CAUTION
        return when (group) {
            Group.NOISE -> {
                val margins = analysis.bands.filter { it.centerHz in info.bands && it.marginDb != null }.map { it.marginDb!! }
                val needs = info.threshold ?: 35.0
                val range = if (margins.isEmpty()) null else {
                    val lo = margins.min().toInt(); val hi = margins.max().toInt()
                    if (lo == hi) "$lo dB" else "$lo–$hi dB"
                }
                val noSnr = ReasonCode.INSUFFICIENT_SNR_MARGIN !in info.codes
                if (everyBand && !caution) {
                    Finding(
                        kind, "The background was too loud.",
                        if (noSnr || range == null) "The decay ran into the noise before it could be measured."
                        else "In every band the clap stood only $range above the noise, and ${num(needs)} dB is needed.",
                        if (range == null) "Room too noisy." else "Room too noisy: the clap was only $range above the noise (needs ${num(needs)}).",
                        ReasonTexts.advice(ReasonCode.INSUFFICIENT_SNR_MARGIN), bands,
                    )
                } else {
                    val lowOnly = info.bands.all { it <= 250 }
                    Finding(
                        kind, "${bandsText(info.bands)} could not be measured.",
                        (if (range != null) "The clap was too weak there compared with the background ($range, needs ${num(needs)})."
                        else "The decay ran into the noise there.") + if (lowOnly) " Low tones often are with a handclap." else "",
                        "${bandsText(info.bands)} not measured: the clap was too weak there.",
                        if (caution) null else ReasonTexts.advice(ReasonCode.INSUFFICIENT_SNR_MARGIN), bands,
                    )
                }
            }
            Group.OTHER_SOUND -> if (info.invalid) {
                val at = beforeClap?.let { sec(it.timeSeconds) }
                Finding(
                    kind, if (beforeClap != null) "Another sound came before the clap." else "Another sound disturbed the recording.",
                    (if (at != null) "At $at s: " else "") + "a tap, movement or a second clap. Because of it the whole recording was rejected." +
                        if (raisedBackground) " It also raised the background the bands are judged against, which is why every band fails the noise check." else "",
                    if (at != null) "Another sound at $at s, before the clap." else "Another sound disturbed the recording.",
                    if (beforeClap != null) "Put the phone down before the countdown ends and stay still. Clap only when it says “Clap once now”."
                    else ReasonTexts.advice(ReasonCode.ADDITIONAL_IMPULSE),
                    bands,
                )
            } else {
                val at = beforeClap?.let { " at ${sec(it.timeSeconds)} s" } ?: ""
                Finding(
                    kind, "A noise came before the clap.", "A tap or movement$at was picked up in the quiet part. It did not stop the measurement.",
                    "A noise before the clap$at.", ReasonTexts.advice(ReasonCode.BACKGROUND_TRANSIENT), bands,
                )
            }
            Group.TOO_LOUD -> if (info.invalid) {
                Finding(
                    kind, "The clap was too loud for the microphone.", "It reached full scale, so the recording is distorted.",
                    "The clap was too loud for the microphone.", ReasonTexts.advice(ReasonCode.CLIPPING), bands,
                )
            } else {
                Finding(
                    kind, "The clap was very loud (${db(analysis.peakDbfs)} dBFS).", "The microphone may have compressed it.",
                    "Very loud (${db(analysis.peakDbfs)} dBFS): the microphone may have compressed it.", ReasonTexts.advice(ReasonCode.NEAR_FULL_SCALE), bands,
                )
            }
            Group.TIMING -> {
                val c = info.codes.first()
                val (title, detail) = when (c) {
                    ReasonCode.NO_IMPULSE_FOUND -> "No clap was found." to "Nothing in the recording was loud enough compared with the background."
                    ReasonCode.INSUFFICIENT_PRE_ROLL -> "The clap came too early." to "There was not enough quiet before it."
                    else -> "The recording ended too soon." to "The decay did not reach the background before the end."
                }
                Finding(kind, title, detail, title, ReasonTexts.advice(c), bands)
            }
            Group.DISTURBED -> Finding(
                kind, "The recording was disturbed.", "A route change, interruption or dropped samples broke it.",
                "The recording was disturbed.", ReasonTexts.advice(ReasonCode.CAPTURE_DISCONTINUITY), bands,
            )
            Group.DECAY -> if (info.invalid) {
                Finding(
                    kind, "The decay could not be fitted${bandsSuffix(info.bands, everyBand)}.",
                    "The level did not fall steadily enough to give a reverberation time.", "The decay could not be fitted.", null, bands,
                )
            } else {
                Finding(
                    kind, "The decay was irregular${bandsSuffix(info.bands, everyBand)}.", "The value is kept, but inspect the decay curve.",
                    "Irregular decay${bandsSuffix(info.bands, everyBand)}.", null, bands,
                )
            }
            Group.OTHER -> Finding(kind, info.firstMessage.ifBlank { "Something else was noted." }, "", info.firstMessage.ifBlank { "See details." }, null, bands)
        }
    }

    private fun bandsSuffix(bands: Set<Int>, every: Boolean) = if (every || bands.isEmpty()) "" else " at ${bandsText(bands)}"

    /** "250 Hz", "250 and 4000 Hz", "250, 500 and 1000 Hz". */
    private fun bandsText(bands: Set<Int>): String {
        val b = bands.sorted()
        return when (b.size) {
            0 -> "The band"
            1 -> "${b[0]} Hz"
            else -> b.dropLast(1).joinToString(", ") + " and ${b.last()} Hz"
        }
    }

    private fun bandLabel(hz: Int, first: Boolean) = if (first) "$hz Hz" else if (hz >= 1000) "${hz / 1000}k" else "$hz"
    private fun db(v: Double) = String.format(Locale.ROOT, "%.1f", v).replace('-', '−')
    private fun sec(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun num(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private val WORDS = listOf("none", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private fun words(n: Int) = WORDS.getOrElse(n) { n.toString() }
}
