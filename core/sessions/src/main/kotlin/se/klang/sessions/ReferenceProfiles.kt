package se.klang.sessions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.log10

/*
 * Reference profiles (issue #27): versioned reverberation targets that results can be described
 * against. A target never changes an analysis, a quality gate or a comparison verdict.
 *
 * Values shipped in the app (BuiltInProfiles) come only from public, freely readable sources,
 * verified against the document and cited in docs/reference-profiles.md. Closed sources may only
 * inform the structure of this model, never its values. Other data enters as a USER profile (the
 * user's own value) or an IMPORTED one (a `klang.reference-profile` file the user loads).
 */

/** What a room is used for. Picks the built-in suggestions; it never changes a result. */
@Serializable
enum class RoomUse { DINING_LIVING, HOME_OFFICE, KITCHEN, OTHER }

/** Where a public or declared value comes from. All text, so an export is self-describing. */
@Serializable
data class Citation(
    /** e.g. "Building Bulletin 93: Acoustic design of schools: performance standards". */
    val title: String,
    /** Publisher or author, e.g. "Department for Education". */
    val publisher: String = "",
    /** e.g. "February 2015". */
    val edition: String = "",
    /** Table, section or row, e.g. "Table 6, row “Kitchen”, new build". */
    val section: String,
    /** e.g. "Open Government Licence v3.0". */
    val licence: String = "",
    val url: String = "",
)

/** Where a profile comes from; decides how it is labelled everywhere. */
@Serializable
sealed interface ProfileSource {
    /** Shipped with the app, from a public source. */
    @Serializable
    @SerialName("BUILT_IN")
    data class BuiltIn(val citation: Citation) : ProfileSource

    /** Entered by the user ("your target"). */
    @Serializable
    @SerialName("USER")
    data class User(val createdAtEpochMs: Long) : ProfileSource

    /**
     * Loaded by the user from a `klang.reference-profile` file. [citation] is what the file
     * declares; the app has not checked it and never presents the profile as built in.
     */
    @Serializable
    @SerialName("IMPORTED")
    data class Imported(val fileName: String, val citation: Citation, val importedAtEpochMs: Long) : ProfileSource
}

/**
 * The target value. Every type gives a nominal value T (seconds) and the range the speech-range
 * average is described against.
 */
@Serializable
sealed interface ReverbTarget {
    /** At most [seconds] (e.g. BB93 "Tmf ≤ 0.8 s"). Range: up to and including T. */
    @Serializable
    @SerialName("UPPER_LIMIT")
    data class UpperLimit(val seconds: Double) : ReverbTarget

    /** T = [seconds]; range T·[lowerFactor] … T·[upperFactor]. */
    @Serializable
    @SerialName("TARGET_WITH_TOLERANCE")
    data class TargetWithTolerance(val seconds: Double, val lowerFactor: Double, val upperFactor: Double) : ReverbTarget

    /**
     * T = [a] · log10(V / m³) + [b], defined only for V in [minVolumeCubicMetres] … [maxVolumeCubicMetres];
     * range T·[lowerFactor] … T·[upperFactor] (an upper limit is lowerFactor 0, upperFactor 1).
     */
    @Serializable
    @SerialName("VOLUME_FUNCTION")
    data class VolumeFunction(
        val a: Double,
        val b: Double,
        val minVolumeCubicMetres: Double,
        val maxVolumeCubicMetres: Double,
        val lowerFactor: Double,
        val upperFactor: Double,
    ) : ReverbTarget
}

/** Which values a target applies to. */
@Serializable
sealed interface BandScope {
    /** The arithmetic mean of the 500, 1000 and 2000 Hz T20 medians (Tmf in BB93). */
    @Serializable
    @SerialName("SPEECH_AVERAGE")
    data object SpeechAverage : BandScope

    /**
     * Each listed band on its own. A band's range is T·lowerFactor … T·upperFactor, with T the
     * target's nominal value; the target's own tolerance factors apply to the speech average only.
     */
    @Serializable
    @SerialName("PER_BAND")
    data class PerBand(val factors: List<BandFactor>) : BandScope
}

@Serializable
data class BandFactor(val centerHz: Int, val lowerFactor: Double, val upperFactor: Double)

/**
 * A versioned reverberation target. [version] increases on any change to a value, so a summary
 * that recorded (id, version) can always say what it was compared with.
 */
@Serializable
data class ReferenceProfile(
    val id: String,
    val version: Int,
    /** e.g. "Meeting room". */
    val name: String,
    val roomUses: List<RoomUse> = emptyList(),
    val source: ProfileSource,
    val target: ReverbTarget,
    val bands: BandScope = BandScope.SpeechAverage,
    /** The conditions the value assumes, e.g. "finished, furnished for normal use, unoccupied". */
    val conditions: String = "",
)

/** A room's chosen target. Built-in profiles are resolved by (id, version); others carry a copy. */
@Serializable
data class ProfileSelection(
    val profileId: String,
    val profileVersion: Int,
    /** Copy of the profile for USER and IMPORTED sources; null for BUILT_IN. */
    val profile: ReferenceProfile? = null,
) {
    /** The selected profile, or null if a built-in (id, version) is unknown to this app version. */
    fun resolve(): ReferenceProfile? = profile ?: BuiltInProfiles.find(profileId, profileVersion)

    companion object {
        fun of(p: ReferenceProfile) = ProfileSelection(p.id, p.version, p.takeUnless { it.source is ProfileSource.BuiltIn })
    }
}

// ---------------------------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------------------------

/** Value ranges a profile must respect, wherever it comes from. */
object ProfileLimits {
    /** Same range the analyser reports T20 in (DECAY_OUT_OF_RANGE outside it). */
    const val MIN_SECONDS = 0.05
    const val MAX_SECONDS = 5.0
    const val MAX_FACTOR = 5.0
    const val MIN_VOLUME_CUBIC_METRES = 1.0
    const val MAX_VOLUME_CUBIC_METRES = 100_000.0
    const val MAX_NAME_LENGTH = 100
    const val MAX_TEXT_LENGTH = 500
    /** Octave bands the analyser produces. */
    val BANDS_HZ = setOf(250, 500, 1000, 2000, 4000)
    val SPEECH_BANDS_HZ = listOf(500, 1000, 2000)
    val ID = Regex("[A-Za-z0-9-]{1,64}")
}

object ProfileValidation {
    /** Every problem with [p], in plain language; empty when the profile is usable. */
    fun problems(p: ReferenceProfile): List<String> {
        val out = ArrayList<String>()
        val l = ProfileLimits
        if (!p.id.matches(l.ID)) out += "id must be 1–64 letters, digits or '-' (found “${p.id}”)"
        if (p.version < 1) out += "version must be 1 or higher (found ${p.version})"
        if (p.name.isBlank()) out += "name is missing"
        if (p.name.length > l.MAX_NAME_LENGTH) out += "name is longer than ${l.MAX_NAME_LENGTH} characters"
        if (p.conditions.length > l.MAX_TEXT_LENGTH) out += "conditions is longer than ${l.MAX_TEXT_LENGTH} characters"
        if (p.roomUses.toSet().size != p.roomUses.size) out += "roomUses lists a use twice"
        when (val s = p.source) {
            is ProfileSource.BuiltIn -> out += citationProblems(s.citation)
            is ProfileSource.Imported -> out += citationProblems(s.citation)
            is ProfileSource.User -> Unit
        }
        fun seconds(name: String, v: Double) {
            if (!v.isFinite() || v < l.MIN_SECONDS || v > l.MAX_SECONDS) out += "$name must be ${l.MIN_SECONDS}–${l.MAX_SECONDS} s (found $v)"
        }
        fun factors(name: String, lower: Double, upper: Double) {
            if (!lower.isFinite() || !upper.isFinite() || lower < 0 || upper <= 0 || upper > l.MAX_FACTOR || lower > upper) {
                out += "$name factors must satisfy 0 ≤ lower ≤ upper ≤ ${l.MAX_FACTOR} with upper > 0 (found $lower, $upper)"
            }
        }
        when (val t = p.target) {
            is ReverbTarget.UpperLimit -> seconds("target seconds", t.seconds)
            is ReverbTarget.TargetWithTolerance -> {
                seconds("target seconds", t.seconds)
                factors("target", t.lowerFactor, t.upperFactor)
                if (t.lowerFactor > 1 || t.upperFactor < 1) out += "a tolerance must include the target itself (lower ≤ 1 ≤ upper)"
            }
            is ReverbTarget.VolumeFunction -> {
                if (!t.a.isFinite() || !t.b.isFinite()) out += "volume function coefficients must be finite numbers"
                val okRange = t.minVolumeCubicMetres.isFinite() && t.maxVolumeCubicMetres.isFinite() &&
                    t.minVolumeCubicMetres >= l.MIN_VOLUME_CUBIC_METRES && t.maxVolumeCubicMetres <= l.MAX_VOLUME_CUBIC_METRES &&
                    t.minVolumeCubicMetres < t.maxVolumeCubicMetres
                if (!okRange) {
                    out += "volume range must lie within ${l.MIN_VOLUME_CUBIC_METRES.toInt()}–${l.MAX_VOLUME_CUBIC_METRES.toInt()} m³ with min < max " +
                        "(found ${t.minVolumeCubicMetres}–${t.maxVolumeCubicMetres})"
                } else if (t.a.isFinite() && t.b.isFinite()) {
                    // log10 is monotonic, so the ends of the range bound T.
                    seconds("target at ${t.minVolumeCubicMetres} m³", TargetEvaluator.volumeTarget(t, t.minVolumeCubicMetres))
                    seconds("target at ${t.maxVolumeCubicMetres} m³", TargetEvaluator.volumeTarget(t, t.maxVolumeCubicMetres))
                }
                factors("target", t.lowerFactor, t.upperFactor)
                if (t.lowerFactor > 1 || t.upperFactor < 1) out += "a tolerance must include the target itself (lower ≤ 1 ≤ upper)"
            }
        }
        when (val b = p.bands) {
            BandScope.SpeechAverage -> Unit
            is BandScope.PerBand -> {
                if (b.factors.isEmpty()) out += "per-band scope lists no bands"
                if (b.factors.map { it.centerHz }.toSet().size != b.factors.size) out += "per-band scope lists a band twice"
                for (f in b.factors) {
                    if (f.centerHz !in l.BANDS_HZ) out += "band ${f.centerHz} Hz is not one of ${l.BANDS_HZ.sorted().joinToString()} Hz"
                    factors("${f.centerHz} Hz", f.lowerFactor, f.upperFactor)
                }
            }
        }
        return out
    }

    fun citationProblems(c: Citation): List<String> {
        val out = ArrayList<String>()
        if (c.title.isBlank()) out += "citation title is missing"
        if (c.section.isBlank()) out += "citation section (table, section or row) is missing"
        listOf(c.title, c.publisher, c.edition, c.section, c.licence, c.url).forEach {
            if (it.length > ProfileLimits.MAX_TEXT_LENGTH) out += "a citation field is longer than ${ProfileLimits.MAX_TEXT_LENGTH} characters"
        }
        return out.distinct()
    }
}

// ---------------------------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------------------------

@Serializable
enum class TargetStatus {
    /** Above the range: for an upper limit, longer than the limit. */
    ABOVE,
    /** Inside the range, limits included. For an upper limit: at or below it. */
    WITHIN,
    /** Below the range (only possible with a lower tolerance). */
    BELOW,
}

@Serializable
enum class NotEvaluableReason {
    /** A band the scope needs has no usable median (invalid or not measured there). */
    BAND_MISSING,
    /** A volume function, but the room has no volume. */
    VOLUME_REQUIRED,
    /** A volume function, but the room's volume is outside the function's range. */
    VOLUME_OUT_OF_RANGE,
}

/** One evaluated quantity: the speech average ([bandHz] null) or one band. */
@Serializable
data class TargetItem(
    /** null = speech-range average of 500/1000/2000 Hz. */
    val bandHz: Int? = null,
    /** The measured value; null when [reason] says why it could not be evaluated. */
    val measuredSeconds: Double? = null,
    val nominalSeconds: Double? = null,
    /** null = no lower bound. */
    val lowerSeconds: Double? = null,
    val upperSeconds: Double? = null,
    val status: TargetStatus? = null,
    /** measured − nominal (signed). */
    val differenceSeconds: Double? = null,
    /** How far outside the range (0 when WITHIN). */
    val outsideBySeconds: Double? = null,
    val reason: NotEvaluableReason? = null,
    /** With BAND_MISSING: which bands. */
    val missingBandsHz: List<Int> = emptyList(),
)

/**
 * Result of comparing measured medians with a profile. Records the profile id and version, so a
 * stored summary keeps saying what it was compared with after the profile changes.
 */
@Serializable
data class TargetEvaluation(
    val profileId: String,
    val profileVersion: Int,
    /** Set when nothing can be evaluated (no volume, volume out of range); [items] is then empty. */
    val reason: NotEvaluableReason? = null,
    /** T after applying the room volume, when known. */
    val nominalSeconds: Double? = null,
    val items: List<TargetItem> = emptyList(),
) {
    val evaluable: Boolean get() = reason == null && items.any { it.status != null }
}

/**
 * Pure, deterministic evaluation: (profile, room volume?, band medians) → result. Band medians are
 * T20 medians in seconds by band centre (Hz); a missing or null entry means the band has no usable
 * value (invalid). Nothing is substituted for a missing band.
 */
object TargetEvaluator {
    fun evaluate(profile: ReferenceProfile, volumeCubicMetres: Double?, bandMedians: Map<Int, Double?>): TargetEvaluation {
        val base = TargetEvaluation(profile.id, profile.version)
        val t = profile.target
        val nominal: Double = when (t) {
            is ReverbTarget.UpperLimit -> t.seconds
            is ReverbTarget.TargetWithTolerance -> t.seconds
            is ReverbTarget.VolumeFunction -> {
                val v = volumeCubicMetres?.takeIf { it.isFinite() && it > 0 } ?: return base.copy(reason = NotEvaluableReason.VOLUME_REQUIRED)
                if (v < t.minVolumeCubicMetres || v > t.maxVolumeCubicMetres) return base.copy(reason = NotEvaluableReason.VOLUME_OUT_OF_RANGE)
                volumeTarget(t, v)
            }
        }
        val (lowerFactor, upperFactor) = when (t) {
            is ReverbTarget.UpperLimit -> null to 1.0
            is ReverbTarget.TargetWithTolerance -> t.lowerFactor to t.upperFactor
            is ReverbTarget.VolumeFunction -> t.lowerFactor to t.upperFactor
        }
        val items = when (val scope = profile.bands) {
            BandScope.SpeechAverage -> {
                val missing = ProfileLimits.SPEECH_BANDS_HZ.filter { bandMedians[it]?.takeIf(Double::isFinite) == null }
                listOf(
                    if (missing.isNotEmpty()) TargetItem(nominalSeconds = nominal, reason = NotEvaluableReason.BAND_MISSING, missingBandsHz = missing)
                    else item(null, speechAverage(bandMedians)!!, nominal, lowerFactor?.times(nominal), upperFactor * nominal),
                )
            }
            is BandScope.PerBand -> scope.factors.sortedBy { it.centerHz }.map { f ->
                val m = bandMedians[f.centerHz]?.takeIf(Double::isFinite)
                if (m == null) TargetItem(f.centerHz, nominalSeconds = nominal, reason = NotEvaluableReason.BAND_MISSING, missingBandsHz = listOf(f.centerHz))
                else item(f.centerHz, m, nominal, (f.lowerFactor * nominal).takeIf { f.lowerFactor > 0 }, f.upperFactor * nominal)
            }
        }
        return base.copy(nominalSeconds = nominal, items = items)
    }

    /** Arithmetic mean of the 500, 1000 and 2000 Hz medians, or null if any is missing. */
    fun speechAverage(bandMedians: Map<Int, Double?>): Double? {
        val v = ProfileLimits.SPEECH_BANDS_HZ.map { bandMedians[it]?.takeIf(Double::isFinite) ?: return null }
        return v.sum() / v.size
    }

    fun volumeTarget(t: ReverbTarget.VolumeFunction, volumeCubicMetres: Double): Double = t.a * log10(volumeCubicMetres) + t.b

    private fun item(band: Int?, measured: Double, nominal: Double, lower: Double?, upper: Double): TargetItem {
        val status = when {
            measured > upper -> TargetStatus.ABOVE
            lower != null && measured < lower -> TargetStatus.BELOW
            else -> TargetStatus.WITHIN
        }
        val outside = when (status) {
            TargetStatus.ABOVE -> measured - upper
            TargetStatus.BELOW -> lower!! - measured
            TargetStatus.WITHIN -> 0.0
        }
        return TargetItem(band, measured, nominal, lower, upper, status, measured - nominal, outside)
    }
}

// ---------------------------------------------------------------------------------------------
// Built-in profiles (public sources only)
// ---------------------------------------------------------------------------------------------

/**
 * Profiles shipped with the app. Only public, freely readable sources, each value verified in the
 * source document. Every value and citation here is listed in docs/reference-profiles.md, and a
 * test checks that this table matches that document.
 *
 * Never edit a value in place: add a new version of the profile and keep the old one in [all], so
 * rooms and summaries that recorded (id, version) still resolve.
 */
object BuiltInProfiles {
    const val BB93_TITLE = "Acoustic design of schools: performance standards (Building Bulletin 93)"
    const val BB93_PUBLISHER = "Department for Education (DfE)"
    const val BB93_EDITION = "February 2015"
    const val BB93_LICENCE = "Open Government Licence v3.0"
    const val BB93_URL = "https://assets.publishing.service.gov.uk/media/5a8170d3e5274a2e8ab54012/BB93_February_2015.pdf"
    /** BB93 §1.5.1: the values apply to rooms that are finished, furnished for normal use, but unoccupied. */
    const val OGL_URL = "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/"

    /**
     * The attribution the Open Government Licence v3.0 asks for. Shown in the app's "About and
     * licences" sheet, written into every export that uses a built-in profile, and repeated in the
     * README and docs/reference-profiles.md (a test checks they match).
     */
    const val BB93_ATTRIBUTION =
        "Contains public sector information licensed under the Open Government Licence v3.0. " +
            "Acoustic targets derived from Building Bulletin 93 (BB93): Acoustic design of schools: performance standards, " +
            "February 2015, published by the Department for Education. © Crown copyright 2015."

    const val BB93_CONDITIONS = "Finished, furnished for normal use, unoccupied (BB93 §1.5.1). Tmf: average of the 500 Hz, 1 kHz and 2 kHz octave bands."

    private fun bb93(id: String, name: String, row: String, seconds: Double, vararg uses: RoomUse) = ReferenceProfile(
        id = id,
        version = 1,
        name = name,
        roomUses = uses.toList(),
        source = ProfileSource.BuiltIn(
            Citation(BB93_TITLE, BB93_PUBLISHER, BB93_EDITION, "Table 6 (§1.5.2), row “$row”, new build, Tmf", BB93_LICENCE, BB93_URL),
        ),
        target = ReverbTarget.UpperLimit(seconds),
        bands = BandScope.SpeechAverage,
        conditions = BB93_CONDITIONS,
    )

    val BB93_MEETING_ROOM = bb93(
        "bb93-meeting-room", "Meeting room",
        "Meeting room, Interviewing/counselling room, video conference room", 0.8,
        RoomUse.DINING_LIVING, RoomUse.HOME_OFFICE, RoomUse.KITCHEN,
    )
    val BB93_SMALL_GROUP_ROOM = bb93(
        "bb93-small-group-room", "Small group room",
        "Nursery school room; Primary school: classroom, class base, general teaching area, small group room, SEN calming room", 0.6,
        RoomUse.DINING_LIVING, RoomUse.KITCHEN,
    )
    val BB93_OFFICE = bb93("bb93-office", "Office", "Office, medical room, staff room", 1.0, RoomUse.HOME_OFFICE)
    val BB93_KITCHEN = bb93("bb93-kitchen", "Kitchen", "Kitchen", 1.5, RoomUse.KITCHEN)

    /** Current version of every built-in profile. */
    val current: List<ReferenceProfile> = listOf(BB93_MEETING_ROOM, BB93_SMALL_GROUP_ROOM, BB93_OFFICE, BB93_KITCHEN)

    /** Every version ever shipped (older versions stay resolvable). */
    val all: List<ReferenceProfile> = current

    fun find(id: String, version: Int): ReferenceProfile? = all.firstOrNull { it.id == id && it.version == version }

    fun isBuiltInId(id: String): Boolean = all.any { it.id == id }
}
