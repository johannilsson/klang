package se.klang.sessions

import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.SyntheticImpulse
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    private val bands = listOf(250, 500, 1000, 2000, 4000)

    private fun config(source: String = "UNPROCESSED", rate: Int = 48_000) = AudioConfiguration(
        requestedSampleRate = 48_000, actualSampleRate = rate, encoding = "PCM_FLOAT", channelCount = 1,
        audioSource = source, unprocessedSupported = source == "UNPROCESSED", sourceFallback = source != "UNPROCESSED",
        inputDevice = InputDeviceInfo(1, "BUILTIN_MIC", "Test mic"), effects = listOf(EffectState("AGC", true, false)),
        deviceManufacturer = "Test", deviceModel = "Phone", androidRelease = "16", androidSdkInt = 36,
    )

    private class Fixture(val store: SessionStore, val room: Room)

    private fun fixture(): Fixture {
        val store = SessionStore(Files.createTempDirectory("klang").toFile())
        val room = store.createRoom("Kitchen")
        listOf("Seat A", "Seat B", "Seat C").forEach { store.addPosition(room.id, it, "Ear height, phone on stand") }
        return Fixture(store, store.room(room.id)!!)
    }

    private fun record(f: Fixture, kind: SessionKind, t60: Double, seedBase: Long, audio: AudioConfiguration = config(), attempts: Int = 5): Pair<Session, List<CaptureAttempt>> {
        val s = f.store.createSession(f.room.id, kind.name.lowercase(), kind, if (kind == SessionKind.TREATMENT) "Rug and curtains" else "")
        val service = AnalysisService(f.store)
        for (p in f.room.positions) for (i in 0 until attempts) {
            // Attempt-to-attempt variation: a different noise realisation and a slightly different T.
            val jitter = 1.0 + 0.02 * ((i % 3) - 1)
            val sig = SyntheticImpulse.generate(
                SyntheticImpulse.Spec(decaySeconds = bands.associateWith { t60 * jitter }, durationSeconds = 3.0, seed = seedBase + i + p.name.hashCode()),
            )
            val a = f.store.addAttempt(s.id, p.id, Wav.toBytes(sig.samples, sig.sampleRate, WavEncoding.FLOAT_32).inputStream(), audioConfiguration = audio)
            service.analyze(a)
        }
        return s to f.store.attempts(s.id)
    }

    @Test
    fun `persisted sessions survive a new store instance`() {
        val f = fixture()
        val (s, _) = record(f, SessionKind.BASELINE, 0.5, 100, attempts = 2)
        val reopened = SessionStore(f.store.root)
        assertEquals("Kitchen", reopened.rooms().single().name)
        assertEquals(3, reopened.room(f.room.id)!!.positions.size)
        val attempts = reopened.attempts(s.id)
        assertEquals(6, attempts.size)
        attempts.forEach {
            assertTrue(reopened.recordingFile(it).exists())
            assertEquals(AnalysisConfiguration.VERSION, it.latestAnalysis()!!.algorithmVersion)
            assertNotNull(it.latestAnalysis()!!.bands.first { b -> b.centerHz == 1000 }.t20)
        }
    }

    @Test
    fun `reanalysis appends a result and never touches the recording`() {
        val f = fixture()
        val (_, attempts) = record(f, SessionKind.BASELINE, 0.5, 200, attempts = 1)
        val a = attempts.first()
        val before = f.store.recordingFile(a).readBytes()
        val v2 = AnalysisConfiguration(version = "0.2.0-test", minMarginT20Db = 40.0)
        val updated = AnalysisService(f.store).analyze(a, v2)
        assertEquals(2, updated.analyses.size)
        assertEquals(AnalysisConfiguration.VERSION, updated.analyses[0].algorithmVersion)
        assertEquals("0.2.0-test", updated.analyses[1].algorithmVersion)
        assertEquals("40.0", updated.analyses[1].configuration["minMarginT20Db"].toString())
        assertContentEquals(before, f.store.recordingFile(a).readBytes())
    }

    @Test
    fun `clear treatment effect with a repeat baseline exceeds variability`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.6, 1000)
        val (r, ra) = record(f, SessionKind.REPEAT_BASELINE, 0.6, 2000)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.35, 3000)
        val c = Comparator().compare(f.room, b, ba, t, ta, r, ra, AnalysisConfiguration.VERSION)
        assertTrue(!c.configurationsIncompatible, c.configurationWarnings.toString())
        val primary = c.cells.filter { it.bandHz in listOf(500, 1000, 2000) }
        assertEquals(9, primary.size)
        primary.forEach { cell ->
            assertEquals(Verdict.DECREASE_EXCEEDS_VARIABILITY, cell.verdict, cell.toString())
            val pct = assertNotNull(cell.percentReduction)
            val expected = (cell.baseline.medianSeconds!! - cell.treatment.medianSeconds!!) / cell.baseline.medianSeconds!! * 100
            assertEquals(expected, pct, 1e-9)
            assertTrue(cell.changeSeconds!! < 0)
        }
        assertEquals(c.cells.size, c.summary.cellsByVerdict.values.sum())
    }

    @Test
    fun `without a repeat baseline the change is inconclusive`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.6, 1000)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.35, 3000)
        val c = Comparator().compare(f.room, b, ba, t, ta, algorithmVersion = AnalysisConfiguration.VERSION)
        c.cells.filter { it.baseline.usable >= 3 && it.treatment.usable >= 3 }.forEach {
            assertEquals(Verdict.INCONCLUSIVE, it.verdict)
            assertNull(it.variabilitySeconds)
            assertNotNull(it.percentReduction) // raw numbers are still shown
        }
    }

    @Test
    fun `no real change is inconclusive`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.5, 1000)
        val (r, ra) = record(f, SessionKind.REPEAT_BASELINE, 0.5, 2000)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.5, 3000)
        val c = Comparator().compare(f.room, b, ba, t, ta, r, ra, AnalysisConfiguration.VERSION)
        c.cells.forEach { assertTrue(it.verdict == Verdict.INCONCLUSIVE || it.verdict == Verdict.INSUFFICIENT_DATA, it.toString()) }
    }

    @Test
    fun `different capture configuration blocks the comparison`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.6, 1000)
        val (r, ra) = record(f, SessionKind.REPEAT_BASELINE, 0.6, 2000)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.35, 3000, audio = config(source = "VOICE_RECOGNITION"))
        val c = Comparator().compare(f.room, b, ba, t, ta, r, ra, AnalysisConfiguration.VERSION)
        assertTrue(c.configurationsIncompatible)
        assertTrue(c.configurationWarnings.any { "different configurations" in it }, c.configurationWarnings.toString())
        c.cells.forEach { assertTrue(it.verdict != Verdict.DECREASE_EXCEEDS_VARIABILITY) }
    }

    @Test
    fun `too few usable attempts is reported as insufficient data`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.6, 1000, attempts = 2)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.35, 3000, attempts = 2)
        val c = Comparator().compare(f.room, b, ba, t, ta, algorithmVersion = AnalysisConfiguration.VERSION)
        c.cells.forEach { assertEquals(Verdict.INSUFFICIENT_DATA, it.verdict) }
    }

    @Test
    fun `export contains json, csv and the original wav bytes`() {
        val f = fixture()
        val (b, ba) = record(f, SessionKind.BASELINE, 0.6, 1000, attempts = 3)
        val (t, ta) = record(f, SessionKind.TREATMENT, 0.35, 3000, attempts = 3)
        val c = Comparator().compare(f.room, b, ba, t, ta, algorithmVersion = AnalysisConfiguration.VERSION)
        val exporter = Exporter(f.store, "test")
        val out = ByteArrayOutputStream()
        exporter.writeZip(exporter.document(f.room.id, listOf(c)), out)
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        assertTrue("results.json" in entries && "summary.csv" in entries && "comparison.csv" in entries)
        val wavs = entries.keys.filter { it.endsWith(".wav") }
        assertEquals(18, wavs.size)
        val first = ba.first()
        assertContentEquals(f.store.recordingFile(first).readBytes(), entries["recordings/${b.id}/${first.id}.wav"])
        val jsonText = String(entries["results.json"]!!)
        assertTrue("\"schema\": \"klang.export\"" in jsonText)
        assertTrue("\"algorithmVersion\": \"${AnalysisConfiguration.VERSION}\"" in jsonText)
        val csv = String(entries["summary.csv"]!!).lines()
        assertTrue(csv.first().startsWith("room,session,"))
        assertEquals(18 * bands.size + 1, csv.count { it.isNotBlank() })
        // No exported quantity is labelled as SPL (the disclaimer may say levels are *not* SPL).
        assertTrue(!Regex("\"[A-Za-z]*(spl|Spl|SPL)[A-Za-z]*\"\\s*:").containsMatchIn(jsonText), "never label levels as SPL")
        assertTrue("dB relative to digital full scale (not dB SPL)" in jsonText)
    }
}
