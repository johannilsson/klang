package se.klang.sessions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Top-level export document. Schema documented in docs/export-format.md. */
@Serializable
data class ExportDocument(
    val schema: String = SCHEMA,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val appVersion: String,
    val units: Map<String, String> = UNITS,
    val disclaimer: String = DISCLAIMER,
    val room: Room,
    val sessions: List<ExportedSession>,
    val comparisons: List<Comparison> = emptyList(),
) {
    companion object {
        const val SCHEMA = "klang.export"
        const val SCHEMA_VERSION = 1
        val UNITS = mapOf(
            "*Seconds" to "s",
            "*EpochMs" to "ms since 1970-01-01T00:00:00Z",
            "*Dbfs" to "dB relative to digital full scale (not dB SPL)",
            "*Db" to "dB (relative)",
            "*Hz" to "Hz (octave band centre frequency)",
            "slopeDbPerSecond" to "dB/s",
            "nonLinearityPermille" to "‰",
        )
        const val DISCLAIMER =
            "Comparative, position-specific decay estimates from handclap excitation on a phone microphone. " +
                "Not a calibrated sound level measurement and not a standards-compliant acoustic survey."
    }
}

@Serializable
data class ExportedSession(val session: Session, val attempts: List<CaptureAttempt>)

/**
 * Writes a ZIP with:
 *  - results.json  — [ExportDocument], every attempt with all of its analyses and curves
 *  - summary.csv   — one row per attempt × band, latest analysis
 *  - comparison.csv — one row per compared cell (if comparisons are included)
 *  - recordings/<session>/<attempt>.wav — the original recordings, byte for byte
 */
class Exporter(private val store: SessionStore, private val appVersion: String) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun document(roomId: String, comparisons: List<Comparison> = emptyList(), now: Long = System.currentTimeMillis()): ExportDocument {
        val room = requireNotNull(store.room(roomId)) { "No room $roomId" }
        val sessions = store.sessions(roomId).map { ExportedSession(it, store.attempts(it.id)) }
        return ExportDocument(exportedAtEpochMs = now, appVersion = appVersion, room = room, sessions = sessions, comparisons = comparisons)
    }

    fun writeZip(doc: ExportDocument, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("results.json"))
            zip.write(json.encodeToString(ExportDocument.serializer(), doc).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.csv"))
            zip.write(summaryCsv(doc).toByteArray())
            zip.closeEntry()
            if (doc.comparisons.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("comparison.csv"))
                zip.write(comparisonCsv(doc).toByteArray())
                zip.closeEntry()
            }
            for (s in doc.sessions) for (a in s.attempts) {
                val f = store.recordingFile(a)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("recordings/${s.session.id}/${a.id}.wav"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun summaryCsv(doc: ExportDocument): String = buildString {
        appendLine(
            listOf(
                "room", "session", "session_kind", "intervention", "position", "attempt_id", "attempt_kind", "sequence",
                "recorded_at_utc", "band_hz", "extended_band", "state", "t20_s", "edt_s_secondary", "margin_db",
                "band_peak_dbfs", "band_background_dbfs", "reasons", "algorithm_id", "algorithm_version",
                "sample_rate_hz", "audio_source", "source_fallback", "input_device", "recording_file",
            ).joinToString(",")
        )
        val positions = doc.room.positions.associateBy { it.id }
        for (s in doc.sessions) for (a in s.attempts) {
            val an = a.latestAnalysis()
            val common = listOf(
                doc.room.name, s.session.name, s.session.kind.name, s.session.intervention,
                positions[a.positionId]?.name ?: a.positionId, a.id, a.kind.name, a.sequence.toString(),
                Instant.ofEpochMilli(a.createdAtEpochMs).toString(),
            )
            val tail = listOf(
                an?.algorithmId.orEmpty(), an?.algorithmVersion.orEmpty(), an?.sampleRate?.toString().orEmpty(),
                a.audioConfiguration?.audioSource.orEmpty(), a.audioConfiguration?.sourceFallback?.toString().orEmpty(),
                a.audioConfiguration?.inputDevice?.let { "${it.type} ${it.productName}" }.orEmpty(),
                "recordings/${s.session.id}/${a.id}.wav",
            )
            if (an == null) {
                appendLine((common + listOf("", "", "NOT_ANALYSED", "", "", "", "", "", "") + tail).joinToString(",") { csv(it) })
                continue
            }
            for (b in an.bands) {
                appendLine(
                    (common + listOf(
                        b.centerHz.toString(), b.extended.toString(), b.state.name,
                        num(b.t20?.seconds), num(b.edt?.seconds), num(b.marginDb),
                        num(b.peakLevelDbfs), num(b.backgroundLevelDbfs),
                        b.reasons.joinToString("; ") { it.code.name },
                    ) + tail).joinToString(",") { csv(it) }
                )
            }
        }
    }

    fun comparisonCsv(doc: ExportDocument): String = buildString {
        appendLine(
            "baseline_session,treatment_session,repeat_session,position,band_hz,extended_band," +
                "baseline_median_t20_s,baseline_iqr_s,baseline_usable,baseline_attempted," +
                "treatment_median_t20_s,treatment_iqr_s,treatment_usable,treatment_attempted," +
                "repeat_median_t20_s,change_s,percent_reduction,variability_s,verdict,notes,policy_version,algorithm_version"
        )
        val names = doc.sessions.associate { it.session.id to it.session.name }
        for (c in doc.comparisons) for (cell in c.cells) {
            appendLine(
                listOf(
                    names[c.baselineSessionId] ?: c.baselineSessionId, names[c.treatmentSessionId] ?: c.treatmentSessionId,
                    c.repeatBaselineSessionId?.let { names[it] ?: it }.orEmpty(), cell.positionName,
                    cell.bandHz.toString(), cell.extendedBand.toString(),
                    num(cell.baseline.medianSeconds), num(cell.baseline.iqrSeconds), cell.baseline.usable.toString(), cell.baseline.attempted.toString(),
                    num(cell.treatment.medianSeconds), num(cell.treatment.iqrSeconds), cell.treatment.usable.toString(), cell.treatment.attempted.toString(),
                    num(cell.repeatBaseline?.medianSeconds), num(cell.changeSeconds), num(cell.percentReduction), num(cell.variabilitySeconds),
                    cell.verdict.name, cell.notes.joinToString(" "), c.policyVersion, c.algorithmVersion,
                ).joinToString(",") { csv(it) }
            )
        }
    }

    private fun num(v: Double?) = v?.let { String.format(Locale.ROOT, "%.4f", it) }.orEmpty()

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
