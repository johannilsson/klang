package se.klang.sessions

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceProfilesTest {
    private val user = ProfileSource.User(0)
    private fun profile(target: ReverbTarget, bands: BandScope = BandScope.SpeechAverage) =
        ReferenceProfile("p", 3, "Test", source = user, target = target, bands = bands)

    private fun medians(m500: Double?, m1000: Double?, m2000: Double?, m250: Double? = null, m4000: Double? = null) =
        mapOf(250 to m250, 500 to m500, 1000 to m1000, 2000 to m2000, 4000 to m4000)

    // ---- Evaluation ----

    @Test
    fun upperLimitOnTheSpeechAverage() {
        val p = profile(ReverbTarget.UpperLimit(0.6))
        val above = TargetEvaluator.evaluate(p, null, medians(0.8, 0.7, 0.69)).items.single()
        assertEquals(null, above.bandHz)
        assertEquals(0.73, above.measuredSeconds!!, 1e-12)
        assertEquals(TargetStatus.ABOVE, above.status)
        assertEquals(0.13, above.differenceSeconds!!, 1e-12)
        assertEquals(0.13, above.outsideBySeconds!!, 1e-12)
        assertNull(above.lowerSeconds)

        val at = TargetEvaluator.evaluate(p, null, medians(0.6, 0.6, 0.6)).items.single()
        assertEquals(TargetStatus.WITHIN, at.status, "the limit itself is within an upper limit")
        assertEquals(0.0, at.outsideBySeconds)

        val below = TargetEvaluator.evaluate(p, null, medians(0.4, 0.5, 0.3)).items.single()
        assertEquals(TargetStatus.WITHIN, below.status, "an upper limit has no lower bound")
        assertEquals(-0.2, below.differenceSeconds!!, 1e-12)

        val e = TargetEvaluator.evaluate(p, null, medians(0.4, 0.5, 0.3))
        assertEquals("p", e.profileId)
        assertEquals(3, e.profileVersion)
        assertTrue(e.evaluable)
    }

    @Test
    fun anInvalidPrimaryBandMakesTheSpeechAverageNotEvaluable() {
        val p = profile(ReverbTarget.UpperLimit(0.6))
        val e = TargetEvaluator.evaluate(p, null, medians(0.8, null, 0.7, m250 = 0.9, m4000 = 0.5))
        val item = e.items.single()
        assertEquals(NotEvaluableReason.BAND_MISSING, item.reason)
        assertEquals(listOf(1000), item.missingBandsHz)
        assertNull(item.measuredSeconds, "no value is substituted for a missing band")
        assertNull(item.status)
        assertFalse(e.evaluable)
        // A band absent from the map is missing too; extended bands are never used for the speech average.
        val absent = TargetEvaluator.evaluate(p, null, mapOf(500 to 0.5, 2000 to 0.5, 250 to 0.5, 4000 to 0.5)).items.single()
        assertEquals(listOf(1000), absent.missingBandsHz)
        assertEquals(listOf(500, 1000, 2000), TargetEvaluator.evaluate(p, null, emptyMap()).items.single().missingBandsHz)
    }

    @Test
    fun targetWithToleranceIsAboveWithinOrBelow() {
        val p = profile(ReverbTarget.TargetWithTolerance(0.5, 0.8, 1.2))
        fun at(v: Double) = TargetEvaluator.evaluate(p, null, medians(v, v, v)).items.single()
        assertEquals(TargetStatus.ABOVE, at(0.7).status)
        assertEquals(0.1, at(0.7).outsideBySeconds!!, 1e-12)
        assertEquals(TargetStatus.WITHIN, at(0.6).status, "upper bound included")
        assertEquals(TargetStatus.WITHIN, at(0.4).status, "lower bound included")
        assertEquals(TargetStatus.BELOW, at(0.3).status)
        assertEquals(0.1, at(0.3).outsideBySeconds!!, 1e-12)
        assertEquals(-0.2, at(0.3).differenceSeconds!!, 1e-12)
        assertEquals(0.4, at(0.5).lowerSeconds!!, 1e-12)
        assertEquals(0.6, at(0.5).upperSeconds!!, 1e-12)
    }

    @Test
    fun volumeFunctionNeedsAVolumeInsideItsRange() {
        // T = 0.5·log10(V) − 0.25: 100 m³ → 0.75 s. Placeholder coefficients, not from any source.
        val p = profile(ReverbTarget.VolumeFunction(0.5, -0.25, 20.0, 500.0, 0.9, 1.1))
        val noVolume = TargetEvaluator.evaluate(p, null, medians(0.7, 0.7, 0.7))
        assertEquals(NotEvaluableReason.VOLUME_REQUIRED, noVolume.reason)
        assertTrue(noVolume.items.isEmpty())
        assertFalse(noVolume.evaluable)
        assertEquals(NotEvaluableReason.VOLUME_OUT_OF_RANGE, TargetEvaluator.evaluate(p, 10.0, medians(0.7, 0.7, 0.7)).reason)
        assertEquals(NotEvaluableReason.VOLUME_OUT_OF_RANGE, TargetEvaluator.evaluate(p, 600.0, medians(0.7, 0.7, 0.7)).reason)

        val e = TargetEvaluator.evaluate(p, 100.0, medians(0.7, 0.7, 0.7))
        assertEquals(0.75, e.nominalSeconds!!, 1e-12)
        val item = e.items.single()
        assertEquals(TargetStatus.WITHIN, item.status)
        assertEquals(0.675, item.lowerSeconds!!, 1e-12)
        assertEquals(TargetStatus.BELOW, TargetEvaluator.evaluate(p, 100.0, medians(0.6, 0.6, 0.6)).items.single().status)
        assertEquals(TargetStatus.ABOVE, TargetEvaluator.evaluate(p, 100.0, medians(0.9, 0.9, 0.9)).items.single().status)
    }

    @Test
    fun perBandScopeEvaluatesEachListedBandAndReportsMissingOnes() {
        val p = profile(
            ReverbTarget.UpperLimit(0.6),
            BandScope.PerBand(listOf(BandFactor(1000, 0.8, 1.2), BandFactor(250, 0.0, 1.5))),
        )
        val e = TargetEvaluator.evaluate(p, null, medians(0.5, 0.75, 0.5, m250 = null))
        assertEquals(listOf(250, 1000), e.items.map { it.bandHz }, "sorted by band")
        val low = e.items[0]
        assertEquals(NotEvaluableReason.BAND_MISSING, low.reason)
        assertEquals(listOf(250), low.missingBandsHz)
        assertNull(low.measuredSeconds)
        val mid = e.items[1]
        assertEquals(TargetStatus.ABOVE, mid.status)
        assertEquals(0.72, mid.upperSeconds!!, 1e-12)
        assertEquals(0.48, mid.lowerSeconds!!, 1e-12)
        assertEquals(0.03, mid.outsideBySeconds!!, 1e-12)
        assertTrue(e.evaluable, "at least one band was evaluated")
        assertNull(TargetEvaluator.evaluate(p, null, medians(0.5, 0.5, 0.5, m250 = 0.8)).items[0].lowerSeconds, "factor 0 = no lower bound")
    }

    @Test
    fun evaluationIsDeterministic() {
        val p = profile(ReverbTarget.TargetWithTolerance(0.5, 0.8, 1.2))
        val m = medians(0.61, 0.52, 0.47)
        assertEquals(TargetEvaluator.evaluate(p, 40.0, m), TargetEvaluator.evaluate(p, 40.0, m))
    }

    // ---- Validation ----

    @Test
    fun validationRejectsOutOfRangeValues() {
        fun problems(t: ReverbTarget, b: BandScope = BandScope.SpeechAverage) = ProfileValidation.problems(profile(t, b))
        assertEquals(emptyList(), problems(ReverbTarget.UpperLimit(0.6)))
        assertTrue(problems(ReverbTarget.UpperLimit(0.0)).isNotEmpty())
        assertTrue(problems(ReverbTarget.UpperLimit(6.0)).isNotEmpty())
        assertTrue(problems(ReverbTarget.UpperLimit(Double.NaN)).isNotEmpty())
        assertTrue(problems(ReverbTarget.TargetWithTolerance(0.5, 1.2, 0.8)).isNotEmpty(), "lower > upper")
        assertTrue(problems(ReverbTarget.TargetWithTolerance(0.5, 1.1, 1.3)).isNotEmpty(), "range must include T")
        assertTrue(problems(ReverbTarget.VolumeFunction(0.5, -0.25, 500.0, 20.0, 0.9, 1.1)).isNotEmpty(), "min > max")
        assertTrue(problems(ReverbTarget.VolumeFunction(5.0, 0.0, 20.0, 500.0, 0.9, 1.1)).isNotEmpty(), "T above 5 s at the top of the range")
        assertTrue(problems(ReverbTarget.UpperLimit(0.6), BandScope.PerBand(emptyList())).isNotEmpty())
        assertTrue(problems(ReverbTarget.UpperLimit(0.6), BandScope.PerBand(listOf(BandFactor(125, 0.5, 1.5)))).isNotEmpty(), "band not analysed")
        assertTrue(problems(ReverbTarget.UpperLimit(0.6), BandScope.PerBand(listOf(BandFactor(500, 0.5, 1.5), BandFactor(500, 0.5, 1.5)))).isNotEmpty())
        assertTrue(ProfileValidation.problems(profile(ReverbTarget.UpperLimit(0.6)).copy(id = "bad id")).isNotEmpty())
        assertTrue(ProfileValidation.problems(profile(ReverbTarget.UpperLimit(0.6)).copy(version = 0)).isNotEmpty())
        assertTrue(ProfileValidation.problems(profile(ReverbTarget.UpperLimit(0.6)).copy(name = " ")).isNotEmpty())
    }

    // ---- Built-in profiles ----

    private fun docsFile(): File {
        val dir = System.getProperty("klang.docsDir")?.let(::File) ?: File("../../docs")
        return File(dir, "reference-profiles.md").also { assertTrue(it.exists(), "missing $it") }
    }

    private fun section(text: String, name: String): List<String> {
        val start = text.indexOf("<!-- $name:start -->")
        val end = text.indexOf("<!-- $name:end -->")
        assertTrue(start >= 0 && end > start, "docs/reference-profiles.md has no $name block")
        return text.substring(start, end).lines().drop(1).filter { it.isNotBlank() }
    }

    @Test
    fun builtInProfilesMatchTheDocumentedTable() {
        val text = docsFile().readText()
        val rows = section(text, "built-in-profiles").drop(2) // header and separator
            .map { line -> line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() } }
        assertEquals(BuiltInProfiles.current.size, rows.size, "one documented row per built-in profile")
        for ((p, row) in BuiltInProfiles.current.zip(rows)) {
            assertEquals(6, row.size, "columns in: $row")
            val (id, version, name, bb93Row, seconds) = row
            val uses = row[5]
            assertEquals("`${p.id}`", id)
            assertEquals(p.version.toString(), version)
            assertEquals(p.name, name)
            val source = p.source as ProfileSource.BuiltIn
            assertEquals("Table 6 (§1.5.2), row “$bb93Row”, new build, Tmf", source.citation.section)
            assertEquals(ReverbTarget.UpperLimit(seconds.toDouble()), p.target)
            assertEquals(BandScope.SpeechAverage, p.bands)
            assertEquals(uses.split(",").map { RoomUse.valueOf(it.trim()) }, p.roomUses)
            assertEquals(emptyList(), ProfileValidation.problems(p))
        }

        val citation = section(text, "bb93-citation").associate { line ->
            val (k, v) = line.removePrefix("- ").split(": ", limit = 2)
            k to v
        }
        for (p in BuiltInProfiles.current) {
            val c = (p.source as ProfileSource.BuiltIn).citation
            assertEquals(citation["Title"], c.title)
            assertEquals(citation["Publisher"], c.publisher)
            assertEquals(citation["Edition"], c.edition)
            assertEquals(citation["Licence"], c.licence)
            assertEquals(citation["URL"], c.url)
        }
    }

    @Test
    fun builtInVersionsResolveAndIdsAreUnique() {
        assertEquals(BuiltInProfiles.all.size, BuiltInProfiles.all.map { it.id to it.version }.toSet().size)
        for (p in BuiltInProfiles.current) {
            assertEquals(p, BuiltInProfiles.find(p.id, p.version))
            assertEquals(ProfileSelection(p.id, p.version, null), ProfileSelection.of(p), "built-in: id and version only")
            assertEquals(p, ProfileSelection.of(p).resolve())
        }
        assertNull(BuiltInProfiles.find("bb93-kitchen", 99))
    }

    // ---- Import ----

    private fun newStore(clock: () -> Long = { 1_000L }) = SessionStore(Files.createTempDirectory("klang-profiles").toFile(), clock)

    private val citation = Citation("A public guide", "Publisher", "2024", "Table 1, living room", "CC BY 4.0", "https://example.org/guide")

    private fun fileText(
        target: String = """{ "type": "TARGET_WITH_TOLERANCE", "seconds": 0.6, "lowerFactor": 0.8, "upperFactor": 1.2 }""",
        citation: String = """{ "title": "A public guide", "section": "Table 1, living room" }""",
        schema: String = "klang.reference-profile", schemaVersion: Int = 1,
        id: String = "guide-living", extra: String = "",
        bands: String = """{ "type": "SPEECH_AVERAGE" }""",
    ) = """
        { "schema": "$schema", "schemaVersion": $schemaVersion,
          "profile": { "id": "$id", "version": 1, "name": "Living room", "roomUses": ["DINING_LIVING"],
                       "citation": $citation, "target": $target, "bands": $bands $extra } }
    """.trimIndent()

    @Test
    fun aValidFileIsImportedAndLabelledWithItsDeclaredCitation() {
        val store = newStore()
        val r = store.importProfile(fileText().toByteArray(), "guide.json")
        assertFalse(r.alreadyPresent)
        val p = r.profile
        assertEquals("guide-living", p.id)
        assertEquals(ReverbTarget.TargetWithTolerance(0.6, 0.8, 1.2), p.target)
        val source = p.source as ProfileSource.Imported
        assertEquals("guide.json", source.fileName)
        assertEquals("A public guide", source.citation.title)
        assertEquals(1_000L, source.importedAtEpochMs)
        assertEquals(listOf(p), store.importedProfiles())

        val again = store.importProfile(fileText().toByteArray(), "copy-of-guide.json")
        assertTrue(again.alreadyPresent, "the same declared profile again changes nothing")
        assertEquals(listOf(p), store.importedProfiles())
    }

    @Test
    fun everyTargetAndBandShapeCanBeImported() {
        val store = newStore()
        store.importProfile(fileText(id = "a", target = """{ "type": "UPPER_LIMIT", "seconds": 0.8 }""").toByteArray(), "a.json")
        store.importProfile(
            fileText(id = "b", target = """{ "type": "VOLUME_FUNCTION", "a": 0.5, "b": -0.25, "minVolumeCubicMetres": 20, "maxVolumeCubicMetres": 500, "lowerFactor": 0, "upperFactor": 1 }""")
                .toByteArray(), "b.json",
        )
        store.importProfile(
            fileText(id = "c", bands = """{ "type": "PER_BAND", "factors": [ { "centerHz": 250, "lowerFactor": 0.5, "upperFactor": 1.5 } ] }""").toByteArray(), "c.json",
        )
        assertEquals(setOf("a", "b", "c"), store.importedProfiles().map { it.id }.toSet())
    }

    @Test
    fun invalidFilesAreRejectedWithAMessageAndNothingIsStored() {
        val store = newStore()
        fun rejected(text: String): String {
            val e = assertFailsWith<ProfileImportException> { store.importProfile(text.toByteArray(), "bad.json") }
            assertEquals(emptyList(), store.importedProfiles(), "nothing stored for: ${e.message}")
            return e.message!!
        }
        assertTrue("citation" in rejected(fileText(citation = """{ "title": "A public guide", "section": " " }""")))
        assertTrue("citation" in rejected(fileText(citation = """{ "title": "", "section": "Table 1" }""")))
        assertTrue("schema" in rejected(fileText(citation = """{ "title": "A public guide" }""")), "section is required")
        assertTrue("0.05–5.0 s" in rejected(fileText(target = """{ "type": "UPPER_LIMIT", "seconds": 12 }""")))
        assertTrue("factors" in rejected(fileText(target = """{ "type": "TARGET_WITH_TOLERANCE", "seconds": 0.6, "lowerFactor": 1.4, "upperFactor": 1.2 }""")))
        assertTrue("Not a reference profile" in rejected(fileText(schema = "klang.export")))
        assertTrue("schema version 2" in rejected(fileText(schemaVersion = 2)))
        assertTrue("schema version 1" in rejected(fileText(extra = """, "unknownField": 1""")), "unknown fields are rejected")
        assertTrue("schema version 1" in rejected(fileText(target = """{ "type": "SOMETHING_ELSE", "seconds": 0.6 }""")))
        assertTrue("built-in" in rejected(fileText(id = "bb93-kitchen")))
        assertTrue("not a JSON document" in rejected("not json"))
        assertTrue("KB" in rejected(" ".repeat(ReferenceProfileFile.MAX_BYTES + 1)))
    }

    @Test
    fun aChangedProfileUnderTheSameVersionIsRejected() {
        val store = newStore()
        store.importProfile(fileText().toByteArray(), "guide.json")
        val e = assertFailsWith<ProfileImportException> {
            store.importProfile(fileText(target = """{ "type": "UPPER_LIMIT", "seconds": 0.8 }""").toByteArray(), "guide.json")
        }
        assertTrue("new version" in e.message!!)
        assertEquals(ReverbTarget.TargetWithTolerance(0.6, 0.8, 1.2), store.importedProfiles().single().target)
    }

    @Test
    fun aWrittenFileReadsBackAsTheSameProfile() {
        val p = profile(ReverbTarget.VolumeFunction(0.5, -0.25, 20.0, 500.0, 0.0, 1.0)).copy(id = "round-trip")
        val back = ProfileImporter.parse(ProfileImporter.toFile(p, citation).toByteArray(), "x.json", 5)
        assertEquals(p.copy(source = ProfileSource.Imported("x.json", citation, 5)), back)
    }

    // ---- Room target, your target, export round trip ----

    @Test
    fun yourTargetKeepsCountingVersionsAcrossOtherSelections() {
        var now = 10L
        val store = newStore { now }
        val room = store.createRoom("Living room")
        val v1 = store.userProfile(room.id, 0.6)
        assertEquals(1, v1.version)
        assertEquals("Your target", v1.name)
        assertTrue(v1.source is ProfileSource.User)
        store.updateRoomTarget(room.id, RoomUse.DINING_LIVING, 45.0, ProfileSelection.of(v1))
        assertEquals(v1, store.room(room.id)!!.target!!.profile)
        assertEquals(1, store.userProfile(room.id, 0.6).version, "same value, same version")

        now = 20L
        store.updateRoomTarget(room.id, RoomUse.DINING_LIVING, 45.0, ProfileSelection.of(BuiltInProfiles.BB93_MEETING_ROOM))
        val r = store.room(room.id)!!
        assertEquals(ProfileSelection("bb93-meeting-room", 1, null), r.target)
        assertEquals(v1, r.userProfile, "kept while a built-in profile is selected")
        assertEquals(20L, r.targetUpdatedAtEpochMs)

        val v2 = store.userProfile(room.id, 0.5)
        assertEquals(2, v2.version, "a new value never reuses an earlier (id, version)")
        assertFailsWith<IllegalArgumentException> {
            store.updateRoomTarget(room.id, null, null, ProfileSelection.of(v2.copy(version = 1)))
        }
        store.updateRoomTarget(room.id, null, null, ProfileSelection.of(v2))
        assertEquals(v2, store.room(room.id)!!.userProfile)
        assertFailsWith<IllegalArgumentException> { store.updateRoomTarget(room.id, null, 0.5, null) }
        assertFailsWith<IllegalArgumentException> { store.updateRoomTarget(room.id, null, null, ProfileSelection("bb93-kitchen", 99, null)) }
    }

    @Test
    fun yourTargetCanBeARangeAndKeepsItsVersionCounting() {
        val store = newStore()
        val room = store.createRoom("Kitchen")
        val upper = store.userProfile(room.id, 0.6)
        assertEquals(ReverbTarget.UpperLimit(0.6), upper.target)
        store.updateRoomTarget(room.id, null, null, ProfileSelection.of(upper))

        val range = store.userProfile(room.id, 0.6, lowerSeconds = 0.4)
        assertEquals(2, range.version, "a different target is a new version")
        assertTrue(range.source is ProfileSource.User)
        val t = range.target as ReverbTarget.TargetWithTolerance
        assertEquals(0.5, t.seconds, 1e-12)
        assertEquals(0.4, t.seconds * t.lowerFactor, 1e-12)
        assertEquals(0.6, t.seconds * t.upperFactor, 1e-12)
        store.updateRoomTarget(room.id, null, null, ProfileSelection.of(range))
        assertEquals(2, store.userProfile(room.id, 0.6, 0.4).version, "same range, same version")

        fun status(v: Double) = TargetEvaluator.evaluate(range, null, medians(v, v, v)).items.single().status
        assertEquals(TargetStatus.ABOVE, status(0.7))
        assertEquals(TargetStatus.WITHIN, status(0.55))
        assertEquals(TargetStatus.BELOW, status(0.3))

        assertFailsWith<IllegalArgumentException> { store.userProfile(room.id, 0.4, lowerSeconds = 0.6) }
        assertFailsWith<IllegalArgumentException> { store.userProfile(room.id, 0.6, lowerSeconds = 0.6) }
        assertFailsWith<IllegalArgumentException> { store.userProfile(room.id, 0.6, lowerSeconds = 0.01) }
    }

    @Test
    fun exportRoundTripKeepsTheProfileItsSourceLabelAndVersion() {
        val source = newStore()
        val room = source.createRoom("Kitchen")
        val imported = source.importProfile(fileText().toByteArray(), "guide.json").profile
        val mine = source.userProfile(room.id, 0.7)
        source.updateRoomTarget(room.id, RoomUse.KITCHEN, 38.5, ProfileSelection.of(mine))
        source.updateRoomTarget(room.id, RoomUse.KITCHEN, 38.5, ProfileSelection.of(imported))

        val exporter = Exporter(source, "test")
        val doc = exporter.document(room.id, now = 1)
        assertEquals(listOf(imported, mine), doc.referenceProfiles)
        val zip = ByteArrayOutputStream().also { exporter.writeZip(doc, it) }.toByteArray()
        val json = String(java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "results.json" }; z.readBytes()
        })
        assertTrue("\"type\": \"IMPORTED\"" in json && "\"type\": \"USER\"" in json, "source labels in the export")

        val target = newStore()
        Importer(target).import(zip.inputStream())
        val back = target.room(room.id)!!
        assertEquals(ProfileSelection.of(imported), back.target)
        assertEquals(imported, back.target!!.resolve())
        assertTrue(back.target!!.resolve()!!.source is ProfileSource.Imported)
        assertEquals(mine, back.userProfile)
        assertEquals(RoomUse.KITCHEN, back.use)
        assertEquals(38.5, back.volumeCubicMetres)

        // A built-in selection travels as (id, version) and resolves to the same profile.
        source.updateRoomTarget(room.id, RoomUse.KITCHEN, null, ProfileSelection.of(BuiltInProfiles.BB93_KITCHEN))
        val builtInDoc = exporter.document(room.id, now = 2)
        assertEquals(BuiltInProfiles.BB93_KITCHEN, builtInDoc.referenceProfiles.first())
        val zip2 = ByteArrayOutputStream().also { exporter.writeZip(builtInDoc, it) }.toByteArray()
        val third = newStore()
        Importer(third).import(zip2.inputStream())
        assertEquals(BuiltInProfiles.BB93_KITCHEN, third.room(room.id)!!.target!!.resolve())
    }

    @Test
    fun anExportWhoseProfileCopyClaimsToBeBuiltInIsRejected() {
        val store = newStore()
        val room = store.createRoom("Kitchen")
        val fake = BuiltInProfiles.BB93_KITCHEN.copy(target = ReverbTarget.UpperLimit(3.0))
        // Written directly: the store's own API refuses this.
        store.saveRoom(store.room(room.id)!!.copy(target = ProfileSelection(fake.id, fake.version, fake)))
        val exporter = Exporter(store, "test")
        val zip = ByteArrayOutputStream().also { exporter.writeZip(exporter.document(room.id, now = 1), it) }.toByteArray()
        val e = assertFailsWith<ImportException> { Importer(newStore()).import(zip.inputStream()) }
        assertTrue("built in" in e.message!!)
    }

    @Test
    fun profilesSerialiseWithTypeDiscriminators() {
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(ReferenceProfile.serializer(), BuiltInProfiles.BB93_MEETING_ROOM)
        assertTrue("\"type\":\"BUILT_IN\"" in text)
        assertTrue("\"type\":\"UPPER_LIMIT\"" in text)
        assertTrue("\"type\":\"SPEECH_AVERAGE\"" in text)
        assertNotNull(json.decodeFromString(ReferenceProfile.serializer(), text))
    }

    @Test
    fun theOglAttributionIsDocumentedAndExportedWithBuiltInProfiles() {
        val docs = docsFile().readText()
        val readme = File(docsFile().parentFile.parentFile, "README.md").readText()
        assertTrue(BuiltInProfiles.BB93_ATTRIBUTION in docs.replace("\n", " "), "docs/reference-profiles.md")
        assertTrue(BuiltInProfiles.BB93_ATTRIBUTION in readme.replace("\n", " "), "README.md")
        assertTrue(BuiltInProfiles.OGL_URL in docs && BuiltInProfiles.OGL_URL in readme)

        val store = newStore()
        val room = store.createRoom("Kitchen")
        val exporter = Exporter(store, "test")
        assertEquals(emptyList(), exporter.document(room.id, now = 1).attributions, "no built-in data, no attribution")
        store.updateRoomTarget(room.id, RoomUse.KITCHEN, null, ProfileSelection.of(BuiltInProfiles.BB93_KITCHEN))
        val a = exporter.document(room.id, now = 1).attributions.single()
        assertTrue(a.startsWith(BuiltInProfiles.BB93_ATTRIBUTION) && BuiltInProfiles.OGL_URL in a)
    }
}
