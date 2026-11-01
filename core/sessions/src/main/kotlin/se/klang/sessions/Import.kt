package se.klang.sessions

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** The archive cannot be imported. Nothing was written to the store. */
class ImportException(message: String) : Exception(message)

/** What an import did. [skipped] lists, in plain language, everything that was not imported and why. */
data class ImportResult(
    val roomId: String,
    val roomName: String,
    val roomCreated: Boolean,
    val positionsAdded: Int,
    val sessionsAdded: Int,
    val sessionsAlreadyPresent: Int,
    val attemptsAdded: Int,
    val attemptsAlreadyPresent: Int,
    val analysesAppended: Int,
    val skipped: List<String>,
) {
    fun summary(): String = buildString {
        append(if (roomCreated) "Imported room “$roomName”" else "Merged into existing room “$roomName”")
        append(": $sessionsAdded new session(s), $attemptsAdded new recording(s)")
        if (positionsAdded > 0 && !roomCreated) append(", $positionsAdded new position(s)")
        if (analysesAppended > 0) append(", $analysesAppended analysis result(s) added to existing recordings")
        append(".")
        if (sessionsAlreadyPresent > 0 || attemptsAlreadyPresent > 0) {
            append(" Already present: $sessionsAlreadyPresent session(s), $attemptsAlreadyPresent recording(s); nothing in them was replaced.")
        }
        if (skipped.isNotEmpty()) append(" Not imported (${skipped.size}): ").append(skipped.joinToString("; "))
    }
}

/**
 * Reads a `klang-export-*.zip` ([Exporter], schema `klang.export` version 1) into a [SessionStore].
 *
 * Only `results.json` and `recordings/<sessionId>/<attemptId>.wav` are read; other entries (CSV,
 * unknown files) are ignored, as are unknown JSON fields and exported comparisons (they are
 * recomputed from the attempts). Recordings are copied byte for byte. Entry names are never used
 * as file paths, and an archive with a path-traversal or absolute entry name is rejected.
 *
 * Id collisions never overwrite a recording or an analysis (policy in docs/export-format.md):
 * an existing room keeps its name and notes and gains missing positions, an existing session keeps
 * its metadata and gains missing attempts, and an existing attempt with an identical recording
 * gains the analyses it does not have yet. Everything else that collides is skipped and reported.
 * Importing the same archive twice therefore changes nothing the second time, and an interrupted
 * import can simply be repeated.
 */
class Importer(private val store: SessionStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The part of [ExportDocument] that import needs; everything else is ignored. */
    @Serializable
    private class ImportDocument(
        val schema: String,
        val schemaVersion: Int,
        val room: Room,
        val sessions: List<ExportedSession> = emptyList(),
    )

    fun import(zip: InputStream): ImportResult {
        val staging = store.newStagingDir()
        try {
            val (text, wavs) = unpack(zip, staging)
            val doc = parse(text ?: throw ImportException("No results.json in the archive: this is not a Klang export."))
            validate(doc)
            return apply(doc, wavs)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unpack(zip: InputStream, staging: File): Pair<String?, Map<String, File>> {
        var text: String? = null
        val wavs = HashMap<String, File>()
        try {
            ZipInputStream(zip).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name
                    if (isUnsafe(name)) throw ImportException("Archive entry “$name” has an unsafe path; the file was not imported.")
                    if (e.isDirectory) continue
                    if (name == "results.json") {
                        if (text != null) throw ImportException("The archive contains results.json twice.")
                        text = readLimited(z, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                        continue
                    }
                    val m = WAV_ENTRY.matchEntire(name) ?: continue
                    val key = "${m.groupValues[1]}/${m.groupValues[2]}"
                    if (key in wavs) throw ImportException("The archive contains recording $key twice.")
                    // Staged under a generated name: the entry name never becomes a file path.
                    val f = File(staging, "${wavs.size}.wav")
                    f.outputStream().use { z.copyTo(it) }
                    wavs[key] = f
                }
            }
        } catch (e: ZipException) {
            throw ImportException("The file is not a valid ZIP archive (${e.message}).")
        }
        return text to wavs
    }

    private fun parse(text: String): ImportDocument {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ImportException("results.json is not a valid JSON document.")
        val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull
        if (schema != ExportDocument.SCHEMA) {
            throw ImportException("Not a Klang export: schema is “${schema ?: "missing"}”, expected “${ExportDocument.SCHEMA}”.")
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != ExportDocument.SCHEMA_VERSION) {
            throw ImportException(
                "Unsupported export schema version ${version ?: "(missing)"}; this app reads version ${ExportDocument.SCHEMA_VERSION}.",
            )
        }
        return try {
            json.decodeFromJsonElement(ImportDocument.serializer(), root)
        } catch (e: SerializationException) {
            throw ImportException("results.json does not match schema version ${ExportDocument.SCHEMA_VERSION}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ImportException("results.json does not match schema version ${ExportDocument.SCHEMA_VERSION}: ${e.message}")
        }
    }

    /** Everything is checked before anything is written, so a rejected archive leaves the store untouched. */
    private fun validate(doc: ImportDocument) {
        fun id(kind: String, v: String) {
            if (!v.matches(SAFE_ID)) throw ImportException("Invalid $kind id “$v” in results.json.")
        }
        id("room", doc.room.id)
        val sessionIds = HashSet<String>()
        val attemptIds = HashSet<String>()
        for (es in doc.sessions) {
            id("session", es.session.id)
            if (!sessionIds.add(es.session.id)) throw ImportException("Session ${es.session.id} appears twice in results.json.")
            if (es.session.roomId != doc.room.id) throw ImportException("Session ${es.session.id} belongs to another room than the exported one.")
            for (a in es.attempts) {
                id("attempt", a.id)
                if (!attemptIds.add(a.id)) throw ImportException("Attempt ${a.id} appears twice in results.json.")
                if (a.sessionId != es.session.id) throw ImportException("Attempt ${a.id} is listed under a session it does not belong to.")
            }
        }
    }

    private fun apply(doc: ImportDocument, wavs: Map<String, File>): ImportResult {
        val skipped = ArrayList<String>()
        var positionsAdded = 0
        var sessionsAdded = 0
        var sessionsPresent = 0
        var attemptsAdded = 0
        var attemptsPresent = 0
        var analysesAppended = 0

        val existingRoom = store.room(doc.room.id)
        if (existingRoom == null) {
            store.saveRoom(doc.room)
            positionsAdded = doc.room.positions.size
        } else {
            val known = existingRoom.positions.map { it.id }.toSet()
            val missing = doc.room.positions.filter { it.id !in known }
            if (missing.isNotEmpty()) store.saveRoom(existingRoom.copy(positions = existingRoom.positions + missing))
            positionsAdded = missing.size
        }

        for (es in doc.sessions) {
            val s = es.session
            val existing = store.session(s.id)
            if (existing != null && existing.roomId != s.roomId) {
                skipped += "session “${s.name}” (its id is already used by a session in another room)"
                continue
            }
            if (existing == null) { store.saveSession(s); sessionsAdded++ } else sessionsPresent++

            val stored = store.attempts(s.id).associateBy { it.id }
            for (a in es.attempts) {
                val label = "recording #${a.sequence} of “${s.name}”"
                val wav = wavs["${s.id}/${a.id}"]
                val have = stored[a.id]
                if (have == null) {
                    if (wav == null) { skipped += "$label (no WAV in the archive)"; continue }
                    val rel = store.recordingPath(s.id, a.id)
                    val target = File(store.root, rel)
                    if (target.exists()) {
                        // Left over from an interrupted import: reuse it only if it is the same recording.
                        if (!sameBytes(target, wav)) { skipped += "$label (a different recording is already stored under its id)"; continue }
                    } else {
                        store.placeRecording(wav, rel)
                    }
                    // The recording is on disk before the attempt record that points to it.
                    store.saveAttempt(a.copy(recordingPath = rel))
                    attemptsAdded++
                    continue
                }
                // Existing attempt: its recording and analyses are never replaced. New analyses are
                // added only when the archive holds the very same recording.
                val local = store.recordingFile(have)
                if (wav == null || !local.exists() || !sameBytes(local, wav)) {
                    attemptsPresent++
                    val extra = a.analyses.count { it !in have.analyses }
                    if (extra > 0) skipped += "$extra analysis result(s) of $label (recording could not be matched byte for byte)"
                    continue
                }
                val newOnes = a.analyses.filter { it !in have.analyses }
                if (newOnes.isNotEmpty()) {
                    store.saveAttempt(have.copy(analyses = (have.analyses + newOnes).sortedBy { it.analyzedAtEpochMs }))
                    analysesAppended += newOnes.size
                }
                attemptsPresent++
            }
        }

        val roomName = (existingRoom ?: doc.room).name
        return ImportResult(
            doc.room.id, roomName, existingRoom == null, positionsAdded, sessionsAdded, sessionsPresent,
            attemptsAdded, attemptsPresent, analysesAppended, skipped,
        )
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw ImportException("results.json is larger than ${limit / (1024 * 1024)} MB.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val c = x.read()
                    if (c != y.read()) return false
                    if (c < 0) return true
                }
            }
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9-]+")
        val WAV_ENTRY = Regex("recordings/([A-Za-z0-9-]+)/([A-Za-z0-9-]+)\\.wav")
        const val MAX_JSON_BYTES = 64 * 1024 * 1024

        fun isUnsafe(name: String): Boolean =
            name.startsWith("/") || name.contains('\\') || name.contains('\u0000') ||
                Regex("^[A-Za-z]:").containsMatchIn(name) || name.split('/').any { it == ".." }
    }
}
