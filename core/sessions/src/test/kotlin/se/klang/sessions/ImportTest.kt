package se.klang.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.SyntheticImpulse
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportTest {
    private val now = 1_700_000_000_000L

    private fun newStore() = SessionStore(Files.createTempDirectory("klang-import").toFile())

    /** A room with two sessions (one archived), clap attempts with analyses and an unanalysed background. */
    private fun source(): Pair<SessionStore, Room> {
        val store = newStore()
        val room = store.createRoom("Kitchen", "Tiled floor")
        val positions = listOf("Seat A", "Seat B").map { store.addPosition(room.id, it, "Ear height", clapSpot = "1.5 m in front, facing the phone") }
        val service = AnalysisService(store)
        listOf(SessionKind.BASELINE to 0.6, SessionKind.TREATMENT to 0.4).forEachIndexed { si, (kind, t60) ->
            val s = store.createSession(
                room.id, kind.name, kind, if (kind == SessionKind.TREATMENT) "Rug" else "",
                RoomConditions(doorsAndWindows = "Closed", appliancesOff = true),
            )
            for (p in positions) {
                repeat(2) { i ->
                    val sig = SyntheticImpulse.generate(
                        SyntheticImpulse.Spec(decaySeconds = listOf(500, 1000, 2000).associateWith { t60 }, durationSeconds = 2.0, seed = 10L * si + i),
                    )
                    val a = store.addAttempt(s.id, p.id, Wav.toBytes(sig.samples, sig.sampleRate, WavEncoding.FLOAT_32).inputStream())
                    service.analyze(a)
                }
                store.addAttempt(s.id, p.id, ByteArray(256) { it.toByte() }.inputStream(), AttemptKind.BACKGROUND)
            }
            if (si == 0) store.setSessionArchived(s.id, true)
        }
        return store to store.room(room.id)!!
    }

    private fun exportZip(store: SessionStore, roomId: String): ByteArray {
        val exporter = Exporter(store, "test")
        return ByteArrayOutputStream().also { exporter.writeZip(exporter.document(roomId, now = now), it) }.toByteArray()
    }

    private fun entries(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
    }.toByteArray()

    private fun allAttempts(store: SessionStore, roomId: String) = store.sessions(roomId).flatMap { store.attempts(it.id) }

    @Test
    fun `export then import into an empty store round-trips the document and the wav bytes`() {
        val (a, room) = source()
        val zip = exportZip(a, room.id)

        val b = newStore()
        val result = Importer(b).import(zip.inputStream())
        assertTrue(result.roomCreated)
        assertEquals(2, result.sessionsAdded)
        assertEquals(12, result.attemptsAdded)
        assertTrue(result.skipped.isEmpty(), result.skipped.toString())

        val docA = Exporter(a, "test").document(room.id, now = now)
        val docB = Exporter(b, "test").document(room.id, now = now)
        assertEquals(docA, docB)
        assertTrue(docB.sessions.first().session.archived)
        assertEquals(listOf("1.5 m in front, facing the phone"), docB.room.positions.map { it.clapSpot }.distinct())
        // The serialised document is identical too, apart from nothing (same exportedAt here).
        assertEquals(String(entries(zip)["results.json"]!!), String(entries(exportZip(b, room.id))["results.json"]!!))

        val attemptsA = allAttempts(a, room.id)
        assertEquals(12, attemptsA.size)
        for (x in attemptsA) {
            val y = b.attempts(x.sessionId).single { it.id == x.id }
            assertContentEquals(a.recordingFile(x).readBytes(), b.recordingFile(y).readBytes())
            assertEquals(x.analyses, y.analyses)
        }
        assertTrue(File(b.root, "import-staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `collisions never overwrite recordings or analyses and only add what is missing`() {
        val (a, room) = source()
        val b = newStore()
        Importer(b).import(exportZip(a, room.id).inputStream())
        val before = allAttempts(b, room.id).associate { it.id to b.recordingFile(it).readBytes() }

        // Same archive again: nothing changes.
        val again = Importer(b).import(exportZip(a, room.id).inputStream())
        assertEquals(false, again.roomCreated)
        assertEquals(0, again.sessionsAdded); assertEquals(2, again.sessionsAlreadyPresent)
        assertEquals(0, again.attemptsAdded); assertEquals(12, again.attemptsAlreadyPresent)
        assertEquals(0, again.analysesAppended)
        assertEquals(Exporter(a, "test").document(room.id, now = now), Exporter(b, "test").document(room.id, now = now))

        // Meanwhile in A: one attempt re-analysed with another version, a new session, a new position, a renamed room.
        val reanalysed = AnalysisService(a).analyze(
            allAttempts(a, room.id).first { it.kind == AttemptKind.IMPULSE },
            AnalysisConfiguration(version = "0.2.0-test", minMarginT20Db = 40.0),
        )
        a.saveRoom(a.room(room.id)!!.copy(name = "Kitchen (renamed elsewhere)"))
        val seat = a.addPosition(room.id, "Seat C")
        val extra = a.createSession(room.id, "Repeat", SessionKind.REPEAT_BASELINE)
        a.addAttempt(extra.id, seat.id, ByteArray(128) { 7 }.inputStream(), AttemptKind.BACKGROUND)

        val merged = Importer(b).import(exportZip(a, room.id).inputStream())
        assertEquals(1, merged.sessionsAdded)
        assertEquals(1, merged.attemptsAdded)
        assertEquals(1, merged.positionsAdded)
        assertEquals(1, merged.analysesAppended)
        assertEquals("Kitchen", b.room(room.id)!!.name) // existing metadata is kept
        assertEquals(3, b.room(room.id)!!.positions.size)
        val inB = b.attempts(reanalysed.sessionId).single { it.id == reanalysed.id }
        assertEquals(listOf(AnalysisConfiguration.VERSION, "0.2.0-test"), inB.analyses.map { it.algorithmVersion })
        allAttempts(b, room.id).filter { it.id in before }.forEach { assertContentEquals(before[it.id], b.recordingFile(it).readBytes()) }

        // An archive whose recording differs under the same id: skipped, local recording and analyses untouched.
        val zip = entries(exportZip(a, room.id))
        val key = "recordings/${reanalysed.sessionId}/${reanalysed.id}.wav"
        zip[key] = zip[key]!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val analysesBefore = b.attempts(reanalysed.sessionId).single { it.id == reanalysed.id }.analyses
        val conflict = Importer(b).import(zipOf(zip).inputStream())
        assertEquals(0, conflict.analysesAppended)
        assertContentEquals(before[reanalysed.id], b.recordingFile(inB).readBytes())
        assertEquals(analysesBefore, b.attempts(reanalysed.sessionId).single { it.id == reanalysed.id }.analyses)
    }

    @Test
    fun `other schemas and versions are rejected and nothing is written`() {
        val (a, room) = source()
        val zip = entries(exportZip(a, room.id))
        val doc = Json.parseToJsonElement(String(zip["results.json"]!!)).jsonObject

        fun withDoc(d: JsonObject) = zipOf(zip + ("results.json" to d.toString().toByteArray()))
        val b = newStore()
        val wrongSchema = assertFailsWith<ImportException> {
            Importer(b).import(withDoc(JsonObject(doc + ("schema" to JsonPrimitive("other.export")))).inputStream())
        }
        assertTrue("other.export" in wrongSchema.message!!)
        val wrongVersion = assertFailsWith<ImportException> {
            Importer(b).import(withDoc(JsonObject(doc + ("schemaVersion" to JsonPrimitive(2)))).inputStream())
        }
        assertTrue("version 2" in wrongVersion.message!!)
        assertFailsWith<ImportException> { Importer(b).import(zipOf(mapOf("summary.csv" to ByteArray(3))).inputStream()) }
        assertFailsWith<ImportException> { Importer(b).import("not a zip at all".byteInputStream()) }
        assertTrue(b.rooms().isEmpty())
    }

    @Test
    fun `unknown fields and entries are ignored`() {
        val (a, room) = source()
        val zip = entries(exportZip(a, room.id))
        val doc = Json.parseToJsonElement(String(zip["results.json"]!!)).jsonObject
        val sessions = doc["sessions"]!!.jsonArray.map { s ->
            val so = s.jsonObject
            val attempts = so["attempts"]!!.jsonArray.map { JsonObject(it.jsonObject + ("futureAttemptField" to JsonPrimitive(1))) }
            JsonObject(so + ("attempts" to JsonArray(attempts)))
        }
        val changed = JsonObject(doc + ("sessions" to JsonArray(sessions)) + ("futureTopLevel" to JsonPrimitive("x")))
        val b = newStore()
        val r = Importer(b).import(zipOf(zip + ("results.json" to changed.toString().toByteArray()) + ("notes/readme.txt" to ByteArray(4))).inputStream())
        assertEquals(12, r.attemptsAdded)
        assertEquals(Exporter(a, "test").document(room.id, now = now), Exporter(b, "test").document(room.id, now = now))
    }

    @Test
    fun `zip-slip entry names are rejected`() {
        val (a, room) = source()
        val zip = entries(exportZip(a, room.id))
        for (evil in listOf("../evil.wav", "recordings/../../evil.wav", "/abs/evil.wav", "recordings\\..\\evil.wav")) {
            val b = newStore()
            val e = assertFailsWith<ImportException> { Importer(b).import(zipOf(mapOf(evil to ByteArray(8)) + zip).inputStream()) }
            assertTrue("unsafe path" in e.message!!, e.message)
            assertTrue(b.rooms().isEmpty())
            assertTrue(!File(b.root.parentFile, "evil.wav").exists())
        }
    }
}
