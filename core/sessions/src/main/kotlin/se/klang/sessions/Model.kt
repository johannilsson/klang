package se.klang.sessions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode

/*
 * Persisted domain model. Everything here is plain data and serialises to JSON.
 * Timestamps are milliseconds since the Unix epoch (UTC). Times are seconds, levels dBFS.
 */

@Serializable
data class Room(
    val id: String,
    val name: String,
    val notes: String = "",
    val createdAtEpochMs: Long,
    val positions: List<MeasurementPosition> = emptyList(),
    /** Hidden from lists; all data kept. Deletion is a separate, explicit step. */
    val archived: Boolean = false,
    /** Last edit of the name, notes or position list (add, reorder, delete); null if never edited. */
    val updatedAtEpochMs: Long? = null,
    /** What the room is used for; picks the suggested targets (#26). Never changes a result. */
    val use: RoomUse? = null,
    /** Optional room volume, m³, for volume-dependent targets and absorption estimates. */
    val volumeCubicMetres: Double? = null,
    /** The reverberation target results are described against (#27); null = none. */
    val target: ProfileSelection? = null,
    /**
     * The latest "your target" (USER) profile of this room, kept even when another target is
     * selected, so its version keeps counting up and an (id, version) never names two values.
     */
    val userProfile: ReferenceProfile? = null,
    /** Last change of [use], [volumeCubicMetres] or [target]; null if never set. */
    val targetUpdatedAtEpochMs: Long? = null,
)

/** A named microphone position, e.g. "Seat by window". Results are never generalised beyond it. */
@Serializable
data class MeasurementPosition(
    val id: String,
    val name: String,
    /** Phone placement: height, orientation, support, case... */
    val placementNotes: String = "",
    /**
     * Where the person stands to clap for this position, e.g. "1.8 m from the phone, toward the kitchen".
     * Part of the position (not the session) so every session repeats the same source location.
     */
    val clapSpot: String = "",
    /**
     * Last edit of the name, placement or clap spot; null if never edited. Edits apply in place: older
     * sessions show the new description, so this time says when it changed.
     */
    val updatedAtEpochMs: Long? = null,
)

@Serializable
enum class SessionKind {
    /** First unchanged-room session. */
    BASELINE,
    /** Unchanged room again, to establish repeatability. */
    REPEAT_BASELINE,
    /** One intervention added. */
    TREATMENT,
}

@Serializable
data class RoomConditions(
    val doorsAndWindows: String = "",
    val furnishings: String = "",
    val occupancy: String = "",
    val appliancesOff: Boolean? = null,
    /**
     * Legacy: per-session clap source description from before clap spots moved to [MeasurementPosition.clapSpot].
     * No longer written by the app; kept so older sessions still show and export it.
     */
    val sourcePosition: String = "",
    val notes: String = "",
)

@Serializable
data class Session(
    val id: String,
    val roomId: String,
    val name: String,
    val kind: SessionKind,
    /** e.g. "One 120×60 cm panel on the wall behind the table". Empty for baselines. */
    val intervention: String = "",
    val conditions: RoomConditions = RoomConditions(),
    val createdAtEpochMs: Long,
    /** Hidden from lists and comparison pickers; all data kept. Deletion is a separate, explicit step. */
    val archived: Boolean = false,
    /** Last edit of the name, kind, intervention or conditions; null if never edited. */
    val updatedAtEpochMs: Long? = null,
)

/**
 * Audio capture configuration, as requested and as actually obtained.
 * Filled by the Android capture layer. Before/after sessions with different values are flagged.
 */
@Serializable
data class AudioConfiguration(
    val requestedSampleRate: Int,
    val actualSampleRate: Int,
    /** "PCM_FLOAT" or "PCM_16BIT". */
    val encoding: String,
    val channelCount: Int,
    /** MediaRecorder.AudioSource name actually used, e.g. "UNPROCESSED" or "VOICE_RECOGNITION". */
    val audioSource: String,
    /** Value of AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED; null if unknown. */
    val unprocessedSupported: Boolean?,
    /** True if we wanted UNPROCESSED but had to fall back. */
    val sourceFallback: Boolean,
    val inputDevice: InputDeviceInfo?,
    val effects: List<EffectState> = emptyList(),
    /** Whether the platform reported the capture as client-silenced or other config details. */
    val recordingConfigNotes: List<String> = emptyList(),
    val bufferSizeFrames: Int? = null,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val androidSdkInt: Int,
) {
    /** Fields that must match for two sessions to be compared without a warning. */
    fun compatibilityKey(): List<String> = listOf(
        "sampleRate=$actualSampleRate",
        "encoding=$encoding",
        "source=$audioSource",
        "device=${deviceManufacturer}/${deviceModel}",
        "input=${inputDevice?.type}/${inputDevice?.address}",
        "effects=" + effects.filter { it.available }.sortedBy { it.name }.joinToString { "${it.name}:${it.enabled}" },
    )
}

@Serializable
data class InputDeviceInfo(
    val id: Int,
    /** AudioDeviceInfo type name, e.g. "BUILTIN_MIC", "USB_DEVICE", "WIRED_HEADSET". */
    val type: String,
    val productName: String,
    val address: String = "",
)

/** A pre-processing effect (AGC, NS, AEC): whether the device offers it and whether it was enabled. */
@Serializable
data class EffectState(val name: String, val available: Boolean, val enabled: Boolean?, val note: String = "")

@Serializable
enum class AttemptKind { BACKGROUND, IMPULSE }

/** Something the capture layer observed during a recording. */
@Serializable
data class CaptureEvent(
    /** e.g. "OVERRUN", "SHORT_READ", "ROUTE_CHANGED", "INTERRUPTED", "CANCELLED", "SILENCED". */
    val type: String,
    val atFrame: Long,
    val detail: String = "",
)

@Serializable
data class CaptureAttempt(
    val id: String,
    val sessionId: String,
    val positionId: String,
    val kind: AttemptKind = AttemptKind.IMPULSE,
    /** 1-based order within its session and position. */
    val sequence: Int,
    val createdAtEpochMs: Long,
    /** Path of the original WAV, relative to the store root. Never overwritten. */
    val recordingPath: String,
    val audioConfiguration: AudioConfiguration? = null,
    val captureEvents: List<CaptureEvent> = emptyList(),
    /** All analyses ever run on this recording; newest last. */
    val analyses: List<AnalysisRecord> = emptyList(),
    val note: String = "",
) {
    fun latestAnalysis(): AnalysisRecord? = analyses.lastOrNull()
    fun analysis(version: String): AnalysisRecord? = analyses.lastOrNull { it.algorithmVersion == version }
}

@Serializable
data class Reason(
    val code: ReasonCode,
    val severity: QualityState,
    val message: String,
    val value: Double? = null,
    val threshold: Double? = null,
)

@Serializable
data class Fit(
    val seconds: Double,
    val slopeDbPerSecond: Double,
    val interceptDb: Double,
    val rangeStartDb: Double,
    val rangeEndDb: Double,
    val startSeconds: Double,
    val endSeconds: Double,
    val rSquared: Double,
    val nonLinearityPermille: Double,
    val residualRmsDb: Double,
)

@Serializable
data class Curve(
    val stepSeconds: Double,
    val edcDb: List<Float>,
    val envelopeDb: List<Float>,
    val backgroundDb: Double,
)

@Serializable
data class BandRecord(
    val centerHz: Int,
    val extended: Boolean,
    val state: QualityState,
    val reasons: List<Reason>,
    val t20: Fit? = null,
    val edt: Fit? = null,
    val t30Diagnostic: Fit? = null,
    val marginDb: Double? = null,
    val peakLevelDbfs: Double? = null,
    val backgroundLevelDbfs: Double? = null,
    val truncationSeconds: Double? = null,
    val compensationDb: Double? = null,
    val curve: Curve? = null,
)

@Serializable
data class AnalysisRecord(
    val algorithmId: String,
    val algorithmVersion: String,
    /** Every threshold that produced this result. */
    val configuration: JsonObject,
    val analyzedAtEpochMs: Long,
    val state: QualityState,
    val reasons: List<Reason>,
    val sampleRate: Int,
    val durationSeconds: Double,
    val onsetSeconds: Double? = null,
    val peakDbfs: Double,
    val backgroundRmsDbfs: Double? = null,
    val bands: List<BandRecord>,
)
