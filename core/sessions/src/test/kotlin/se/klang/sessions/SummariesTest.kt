package se.klang.sessions

import kotlinx.serialization.json.Json
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Text rules of [Summarizer] on hand-built results. Synthetic engineering fixtures: none of these
 * values is a real-room result.
 */
class SummariesTest {
    private val version = AnalysisConfiguration().version
    private val config = AudioConfiguration(
        requestedSampleRate = 48_000, actualSampleRate = 48_000, encoding = "PCM_FLOAT", channelCount = 1,
        audioSource = "UNPROCESSED", unprocessedSupported = true, sourceFallback = false, inputDevice = null,
        deviceManufacturer = "test", deviceModel = "test", androidRelease = "16", androidSdkInt = 36,
    )
    private val seatA = MeasurementPosition("pos-a", "Seat A")
    private val seatB = MeasurementPosition("pos-b", "Seat B")
    private fun room(target: ReferenceProfile? = null, volume: Double? = null, vararg positions: MeasurementPosition = arrayOf(seatA)) =
        Room("room-1", "Kitchen", createdAtEpochMs = 0, positions = positions.toList(), volumeCubicMetres = volume, target = target?.let(ProfileSelection::of))

    private fun session(id: String, kind: SessionKind, intervention: String = "") = Session(id, "room-1", id, kind, intervention, createdAtEpochMs = 0)

    private var n = 0
    private fun fit(s: Double) = Fit(s, -60 / s, 0.0, -5.0, -25.0, 0.05, 0.4, 0.99, 1.0, 0.1)

    /** One clap. [t20] by band: a value = valid band, null = invalid with [bandReason]; absent bands are not in the record. */
    private fun clap(
        sessionId: String, position: MeasurementPosition, t20: Map<Int, Double?>,
        bandReason: ReasonCode = ReasonCode.INSUFFICIENT_SNR_MARGIN, attemptReason: ReasonCode? = null,
        questionable: Set<Int> = emptySet(), staleFit: Double? = null,
    ): CaptureAttempt {
        val attemptReasons = listOfNotNull(attemptReason?.let { Reason(it, QualityState.INVALID, "attempt: $it") })
        val bands = t20.map { (hz, v) ->
            when {
                attemptReason != null -> BandRecord(hz, hz == 250 || hz == 4000, QualityState.INVALID, attemptReasons, t20 = staleFit?.let(::fit))
                v == null -> BandRecord(hz, hz == 250 || hz == 4000, QualityState.INVALID, listOf(Reason(bandReason, QualityState.INVALID, "band: $bandReason")), t20 = staleFit?.let(::fit))
                hz in questionable -> BandRecord(hz, hz == 250 || hz == 4000, QualityState.QUESTIONABLE, listOf(Reason(ReasonCode.NON_LINEAR_DECAY, QualityState.QUESTIONABLE, "q")), t20 = fit(v))
                else -> BandRecord(hz, hz == 250 || hz == 4000, QualityState.VALID, emptyList(), t20 = fit(v))
            }
        }
        val record = AnalysisRecord(
            "klang.schroeder-truncated", version, Records.configuration(AnalysisConfiguration()), 0,
            if (attemptReason != null) QualityState.INVALID else QualityState.VALID, attemptReasons, 48_000, 5.0, 1.5, -12.0, -70.0, bands,
        )
        n++
        return CaptureAttempt("att-$n", sessionId, position.id, AttemptKind.IMPULSE, n, n.toLong(), "x", config, analyses = listOf(record))
    }

    private fun bands(b250: Double?, b500: Double, b1000: Double, b2000: Double, b4000: Double?) =
        mapOf(250 to b250, 500 to b500, 1000 to b1000, 2000 to b2000, 4000 to b4000)

    /** [count] claps with the given band values, each shifted a little so medians are exact. */
    private fun claps(sessionId: String, position: MeasurementPosition, values: Map<Int, Double?>, count: Int = 5, spread: Double = 0.01, questionable: Set<Int> = emptySet()) =
        (0 until count).map { i ->
            val d = (i - count / 2) * spread
            clap(sessionId, position, values.mapValues { (_, v) -> v?.plus(d) }, questionable = questionable)
        }

    private val summarizer = Summarizer()

    private fun summary(room: Room, attempts: List<CaptureAttempt>, profile: ReferenceProfile? = room.target?.resolve()) =
        summarizer.session(room, session("s1", SessionKind.BASELINE), attempts, version, profile)

    // ---- Session summaries: band shapes ----

    @Test
    fun midLowHeavyShapeGivesTheThickAbsorberHint() {
        val s = summary(room(), claps("s1", seatA, bands(null, 0.78, 0.74, 0.66, 0.59)))
        val p = s.positions.single()
        assertEquals("After a clap, sound takes about 0.7 s to die away in the speech range at Seat A.", p.headline)
        assertEquals("It is longest in the mid and low tones and shorter in the high tones.", p.shape)
        assertEquals(listOf("250 Hz: not measured (the claps were too weak there compared with the background)."), p.unmeasured)
        assertNull(p.bands.first { it.bandHz == 250 }.medianSeconds)
        assertEquals(0.78, p.bands.first { it.bandHz == 500 }.medianSeconds!!, 1e-9)
        assertTrue(s.hints.any { "thick porous absorbers" in it && "Thin materials barely reach them" in it })
        assertTrue(s.hints.any { "Low tones (250 Hz) can't be judged" in it })
        assertNull(p.target, "no target, no target part")
    }

    @Test
    fun highHeavyShapeGivesTheSoftFurnishingHint() {
        val s = summary(room(), claps("s1", seatA, bands(0.5, 0.5, 0.55, 0.7, 0.75)))
        assertEquals("It is longest in the high tones (2000–4000 Hz).", s.positions.single().shape)
        assertTrue(s.hints.single().startsWith("The high tones ring longest. Soft furnishings"))
    }

    @Test
    fun evenShapeAboveTheTargetGivesTheGeneralHintAndASabineEstimate() {
        val target = BuiltInProfiles.BB93_SMALL_GROUP_ROOM
        val s = summary(room(target, volume = 45.0), claps("s1", seatA, bands(0.8, 0.8, 0.8, 0.8, 0.8)))
        val p = s.positions.single()
        assertEquals("It is about the same across the measured bands.", p.shape)
        assertTrue(s.hints.any { "More absorbing area in general" in it })
        // ΔA = 0.161 · 45 · (1/0.6 − 1/0.8) = 3.02 m² → "3"
        val sabine = s.hints.single { "Sabine estimate" in it }
        assertTrue("For this 45 m³ room, roughly 3 m² more of fully absorbing surface" in sabine, sabine)
        assertEquals(0.1611, Sabine.constant(343.0), 1e-4)
        assertEquals(Sabine.constant(343.0) * 45 * (1 / 0.6 - 1 / 0.8), Sabine.extraAbsorptionSquareMetres(45.0, 0.8, 0.6)!!, 1e-12)
    }

    @Test
    fun evenShapeWithoutTargetGivesNoShapeHintAndNoVolumeNoSabine() {
        val even = summary(room(), claps("s1", seatA, bands(0.8, 0.8, 0.8, 0.8, 0.8)))
        assertEquals(emptyList(), even.hints)
        val noVolume = summary(room(BuiltInProfiles.BB93_SMALL_GROUP_ROOM), claps("s1", seatA, bands(0.8, 0.8, 0.8, 0.8, 0.8)))
        assertTrue(noVolume.hints.none { "Sabine" in it }, "no volume, no estimate")
        assertNull(Sabine.extraAbsorptionSquareMetres(45.0, 0.5, 0.6), "already below the target")
    }

    // ---- Against the target ----

    @Test
    fun aboveAtAndBelowTheTargetWithBuiltInWording() {
        val target = BuiltInProfiles.BB93_SMALL_GROUP_ROOM // ≤ 0.6 s
        fun text(v: Double) = summary(room(target), claps("s1", seatA, bands(null, v, v, v, null), spread = 0.0)).positions.single().target!!
        val above = text(0.73)
        assertEquals("UK school guidance for small group rooms (BB93, Table 6)", above.label)
        assertTrue("Speech-range average at Seat A: 0.73 s (500, 1000 and 2000 Hz)." in above.text, above.text)
        assertTrue("is at most 0.60 s, so it is 0.13 s above it." in above.text, above.text)
        assertTrue("a guide, not a pass or fail" in above.text)
        assertEquals("bb93-small-group-room", above.profileId)
        assertEquals(1, above.profileVersion)
        assertTrue("so it is at the target." in text(0.6).text)
        assertTrue("so it is 0.20 s below it." in text(0.4).text)
        val within = summary(room(target), claps("s1", seatA, bands(null, 0.5, 0.5, 0.5, null)))
        assertTrue(within.hints.first().startsWith("Every measured position is at or below the target"), "at or below: no suggestions")
        assertTrue(within.hints.none { "Sabine" in it || "thick porous" in it || "rings longest" in it }, within.hints.toString())
        assertTrue(within.hints.any { it.startsWith("If you still hear a ringing echo, it may be a flutter echo") }, "the average can't show a flutter echo")
        // Above the target the shape hints apply instead, and the echo line is not added to them.
        assertTrue(summary(room(target), claps("s1", seatA, bands(null, 0.8, 0.8, 0.8, null))).hints.none { "ringing echo" in it })
    }

    @Test
    fun userAndImportedProfilesAreWordedAsSuch() {
        val mine = ReferenceProfile("user-room-1", 2, "Your target", source = ProfileSource.User(0), target = ReverbTarget.UpperLimit(0.5))
        val t = summary(room(mine), claps("s1", seatA, bands(null, 0.7, 0.7, 0.7, null))).positions.single().target!!
        assertEquals("your target", t.label)
        assertTrue("Your target is at most 0.50 s, so it is 0.20 s above it." in t.text, t.text)
        assertEquals(2, t.profileVersion)

        val imported = ReferenceProfile(
            "guide", 1, "Living room", source = ProfileSource.Imported("guide.json", Citation("A public guide", section = "Table 1"), 0),
            target = ReverbTarget.TargetWithTolerance(0.6, 0.8, 1.2),
        )
        val i = summary(room(imported), claps("s1", seatA, bands(null, 0.4, 0.4, 0.4, null), spread = 0.0)).positions.single().target!!
        assertEquals("“Living room” from A public guide, Table 1 (imported)", i.label)
        assertTrue("is 0.48–0.72 s, so it is 0.08 s below that range." in i.text, i.text)
        assertTrue("The app has not checked it." in ProfileWording.footnote(imported))
        assertFalse("standard for your home" in ProfileWording.label(BuiltInProfiles.BB93_KITCHEN))
    }

    @Test
    fun yourOwnRangeIsWordedAsARangeAndAShorterPositionGetsNoMoreAbsorption() {
        val range = ReferenceProfile(
            "user-room-1", 2, "Your target", source = ProfileSource.User(0),
            target = ReverbTarget.TargetWithTolerance(0.5, 0.8, 1.2),
        )
        fun at(v: Double) = summary(room(range), claps("s1", seatA, bands(null, v, v, v, null), spread = 0.0)).positions.single()
        val above = at(0.73)
        assertTrue("Your target is 0.40–0.60 s, so it is 0.13 s above that range." in above.target!!.text, above.target!!.text)
        assertEquals("Seat A: about 0.7 s in the speech range, 0.13 s above your target. 5 of 5 claps usable.", above.brief)
        assertTrue("so it is within it." in at(0.55).target!!.text)
        val below = summary(room(range), claps("s1", seatA, bands(null, 0.3, 0.3, 0.3, null), spread = 0.0))
        assertTrue("so it is 0.10 s below that range." in below.positions.single().target!!.text)
        assertTrue(below.positions.single().brief.contains("0.10 s below the range of your target"), below.positions.single().brief)
        assertTrue(below.hints.any { it == "Seat A is already shorter than the range of the target; more absorption there would make it drier still." }, below.hints.toString())
        assertTrue(below.hints.none { "Sabine" in it })
        assertTrue(at(0.55).let { summary(room(range, 45.0), claps("s1", seatA, bands(null, 0.55, 0.55, 0.55, null), spread = 0.0)).hints }.none { "drier" in it })
    }

    @Test
    fun anInvalidPrimaryBandMakesTheTargetNotEvaluable() {
        val s = summary(room(BuiltInProfiles.BB93_MEETING_ROOM), claps("s1", seatA, mapOf(500 to 0.7, 1000 to null, 2000 to 0.6)))
        val p = s.positions.single()
        assertTrue(p.headline.startsWith("The speech range could not be measured at Seat A: 1000 Hz: the claps were too weak"), p.headline)
        assertNull(p.shape)
        assertNull(p.speechAverageSeconds)
        assertFalse(p.target!!.evaluation.evaluable)
        assertTrue(p.target!!.text.startsWith("Seat A can't be compared with UK school guidance for meeting rooms (BB93, Table 6): 1000 Hz"), p.target!!.text)
        assertEquals(emptyList(), s.hints, "nothing measured, nothing to suggest")
    }

    @Test
    fun aVolumeTargetWithoutVolumeSaysWhy() {
        val p = ReferenceProfile("vf", 1, "Volume", source = ProfileSource.Imported("v.json", Citation("Guide", section = "1"), 0),
            target = ReverbTarget.VolumeFunction(0.5, -0.25, 20.0, 500.0, 0.0, 1.0))
        val t = summary(room(p), claps("s1", seatA, bands(null, 0.7, 0.7, 0.7, null))).positions.single().target!!
        assertTrue("depends on the room volume" in t.text)
    }

    // ---- Quality and positions ----

    @Test
    fun recordingQualityAggregatesReasonsAndPointsToTheCommonFix() {
        val good = claps("s1", seatA, bands(null, 0.7, 0.7, 0.6, null), count = 3)
        val bad = listOf(
            clap("s1", seatA, bands(null, 0.7, 0.7, 0.6, null), attemptReason = ReasonCode.BACKGROUND_TRANSIENT),
            clap("s1", seatA, bands(null, 0.7, 0.7, 0.6, null), attemptReason = ReasonCode.BACKGROUND_TRANSIENT),
        )
        val p = summary(room(), good + bad).positions.single()
        assertEquals("3 of 5 claps usable in the speech range; two had a noise before the clap.", p.quality)
        assertEquals(ReasonTexts.advice(ReasonCode.BACKGROUND_TRANSIENT), p.advice)
    }

    @Test
    fun tooFewUsableClapsGiveNoValue() {
        val p = summary(room(), claps("s1", seatA, bands(null, 0.7, 0.7, 0.6, null), count = 2)).positions.single()
        assertNull(p.speechAverageSeconds)
        assertTrue("only 2 usable claps, needs 3" in p.headline, p.headline)
        assertTrue(p.bands.all { it.medianSeconds == null })
    }

    @Test
    fun questionableBandsAreNamed() {
        val p = summary(room(), claps("s1", seatA, bands(null, 0.7, 0.7, 0.6, 0.5), questionable = setOf(4000))).positions.single()
        assertEquals("Questionable at 4000 Hz: the value is kept, but inspect the decay curves.", p.questionable)
        assertTrue(p.bands.first { it.bandHz == 4000 }.questionable)
    }

    @Test
    fun positionsThatDifferALotPointToTheWorstOne() {
        val r = room(null, null, seatA, seatB)
        val s = summary(r, claps("s1", seatA, bands(null, 0.9, 0.9, 0.9, null)) + claps("s1", seatB, bands(null, 0.6, 0.6, 0.6, null)))
        assertTrue(s.hints.any { it == "Seat A rings longest of the measured positions. Start near it." }, s.hints.toString())
        val close = summary(r, claps("s1", seatA, bands(null, 0.65, 0.65, 0.65, null)) + claps("s1", seatB, bands(null, 0.6, 0.6, 0.6, null)))
        assertTrue(close.hints.none { "rings longest of the measured" in it })
    }

    @Test
    fun aStaleFitOnAnInvalidBandIsNeverUsed() {
        val attempts = claps("s1", seatA, bands(null, 0.7, 0.7, 0.6, null)).map { a ->
            a.copy(analyses = a.analyses.map { an -> an.copy(bands = an.bands.map { b -> if (b.centerHz == 250) b.copy(t20 = fit(0.87)) else b }) })
        }
        val s = summary(room(), attempts)
        assertNull(s.positions.single().bands.first { it.bandHz == 250 }.medianSeconds)
        assertFalse("0.87" in Json.encodeToString(SessionSummary.serializer(), s))
    }

    @Test
    fun summariesAreDeterministicAndVersioned() {
        val a = claps("s1", seatA, bands(0.6, 0.78, 0.74, 0.66, 0.59))
        val r = room(BuiltInProfiles.BB93_MEETING_ROOM, 45.0)
        val s = summary(r, a)
        assertEquals(s, summary(r, a))
        assertEquals(SummaryPolicy.VERSION, s.policyVersion)
        assertEquals(version, s.algorithmVersion)
        assertEquals("bb93-meeting-room", s.profileId)
        assertEquals(1, s.profileVersion)
        assertTrue(s.notes.any { "Not yet checked against reference equipment" in it })
        assertTrue(s.notes.any { "fluttering echo" in it })
    }

    @Test
    fun theBriefLineIsOneSentencePerPositionAndMatchesTheDetails() {
        val target = BuiltInProfiles.BB93_SMALL_GROUP_ROOM
        val r = room(target, null, seatA, seatB)
        val s = summary(r, claps("s1", seatA, bands(null, 0.73, 0.73, 0.73, null), spread = 0.0) + claps("s1", seatB, bands(null, 0.5, 0.5, 0.5, null), spread = 0.0))
        assertEquals("Seat A: about 0.7 s in the speech range, 0.13 s above the target. 5 of 5 claps usable.", s.positions[0].brief)
        assertEquals("Seat B: about 0.5 s in the speech range, within the target. 5 of 5 claps usable.", s.positions[1].brief)
        val own = ReferenceProfile("user-room-1", 1, "Your target", source = ProfileSource.User(0), target = ReverbTarget.UpperLimit(0.5))
        assertTrue(summary(room(own), claps("s1", seatA, bands(null, 0.73, 0.73, 0.73, null), spread = 0.0)).positions.single().brief.contains("above your target"))
        assertEquals("Seat A: about 0.7 s in the speech range. 5 of 5 claps usable.",
            summary(room(), claps("s1", seatA, bands(null, 0.73, 0.73, 0.73, null), spread = 0.0)).positions.single().brief)
        // Some usable claps but fewer than a value needs: say how many are missing, not "could not be measured".
        val few = summary(room(target), claps("s1", seatA, bands(null, 0.7, 0.7, 0.7, null), count = 2)).positions.single()
        assertEquals("Seat A: 2 of 2 claps usable so far; 3 are needed for a value.", few.brief)
        val none = summary(room(target), claps("s1", seatA, mapOf(500 to 0.7, 1000 to null, 2000 to 0.6))).positions.single()
        assertEquals("Seat A: the speech range could not be measured. 0 of 5 claps usable.", none.brief)
    }

    // ---- Comparisons ----

    private fun compare(
        baseline: Map<Int, Double?>, treatment: Map<Int, Double?>, repeat: Map<Int, Double?>? = baseline,
        intervention: String = "Curtains", treatmentConfig: AudioConfiguration = config,
        positions: List<MeasurementPosition> = listOf(seatA), target: ReferenceProfile? = null,
    ): Pair<Comparison, ComparisonText> {
        val r = room(target, null, *positions.toTypedArray())
        val b = session("base", SessionKind.BASELINE)
        val t = session("treat", SessionKind.TREATMENT, intervention)
        val rep = repeat?.let { session("rep", SessionKind.REPEAT_BASELINE) }
        val ba = positions.flatMap { claps("base", it, baseline) }
        val ta = positions.flatMap { claps("treat", it, treatment).map { a -> a.copy(audioConfiguration = treatmentConfig) } }
        val ra = rep?.let { positions.flatMap { p -> claps("rep", p, repeat) } }.orEmpty()
        val c = Comparator().compare(r, b, ba, t, ta, rep, ra, version)
        return c to summarizer.comparison(c, r, listOfNotNull(b, t, rep))
    }

    @Test
    fun aDecreaseThatExceedsTheVariationIsDescribedWithItsSize() {
        val (c, text) = compare(bands(null, 0.78, 0.74, 0.66, 0.59), bands(null, 0.70, 0.62, 0.49, 0.42))
        assertTrue(c.cells.filter { it.bandHz in listOf(500, 1000, 2000) }.all { it.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY })
        assertEquals("After “Curtains”, reverberation in the speech range went down by more than the normal variation at 1 of 1 position.", text.headline)
        val p = text.positions.single()
        assertEquals(Verdict.DECREASE_EXCEEDS_VARIABILITY, p.verdict)
        assertEquals("Down by more than the normal variation (about 0.12 s, 17 %).", p.statement)
        assertTrue(p.where!!.startsWith("Changed by more than the normal variation at 2000 Hz, 4000 Hz, 1000 Hz, 500 Hz"), p.where)
        assertTrue("typical for soft furnishings such as curtains" in p.where!!, p.where)
        assertNotNull(text.variation)
        assertTrue(text.nextSteps.isEmpty())
        assertTrue(text.notes.any { "descriptive rule, not a statistical test" in it })
    }

    @Test
    fun theBeforeAfterDiagramDataHasNoNumberWhereThereAreTooFewOrInvalidClaps() {
        val (_, text) = compare(bands(null, 0.78, 0.74, 0.66, 0.59), bands(null, 0.70, 0.62, 0.49, 0.42))
        val bands = text.positions.single().bands
        assertEquals(listOf(250, 500, 1000, 2000, 4000), bands.map { it.bandHz })
        assertNull(bands[0].beforeSeconds, "250 Hz was never measured")
        assertNull(bands[0].afterSeconds)
        assertEquals(0.78, bands[1].beforeSeconds!!, 1e-9)
        assertEquals(0.70, bands[1].afterSeconds!!, 1e-9)
        assertEquals(0.42, bands[4].afterSeconds!!, 1e-9)
        // A stale fit on an invalid band is never drawn.
        val r = room(null, null, seatA)
        val ba = claps("base", seatA, bands(null, 0.7, 0.7, 0.6, null)).map { a ->
            a.copy(analyses = a.analyses.map { an -> an.copy(bands = an.bands.map { b -> if (b.centerHz == 250) b.copy(t20 = fit(0.87)) else b }) })
        }
        val ta = claps("treat", seatA, bands(null, 0.5, 0.5, 0.5, null))
        val c = Comparator().compare(r, session("base", SessionKind.BASELINE), ba, session("treat", SessionKind.TREATMENT), ta, null, emptyList(), version)
        val low = summarizer.comparison(c, r, emptyList()).positions.single().bands.first { it.bandHz == 250 }
        assertNull(low.beforeSeconds)
        assertNull(low.afterSeconds)
    }

    @Test
    fun theInterventionNoteAppearsOnlyWhenThePatternFits() {
        // Curtains, but the change is in the mid tones: no "typical for curtains" note.
        val (_, text) = compare(bands(null, 0.9, 0.9, 0.6, 0.55), bands(null, 0.6, 0.6, 0.55, 0.5))
        val where = text.positions.single().where!!
        assertTrue("biggest change is in the mid tones." in where, where)
        assertFalse("curtains" in where)
    }

    @Test
    fun noRepeatBaselineGivesInconclusiveAndTheNextStep() {
        val (_, text) = compare(bands(null, 0.78, 0.74, 0.66, null), bands(null, 0.5, 0.5, 0.45, null), repeat = null)
        assertEquals("Change is inconclusive at every position.", text.headline)
        assertEquals("Change is inconclusive.", text.positions.single().statement)
        assertTrue(text.nextSteps.any { it.startsWith("Record a repeat baseline with the room unchanged.") })
        assertNull(text.positions.single().where, "nothing exceeded, nothing described as changed")
    }

    @Test
    fun incompatibleConfigurationsSayWhich() {
        val other = config.copy(audioSource = "VOICE_RECOGNITION", sourceFallback = true)
        val (c, text) = compare(bands(null, 0.78, 0.74, 0.66, null), bands(null, 0.5, 0.5, 0.45, null), treatmentConfig = other)
        assertTrue(c.configurationsIncompatible)
        val step = text.nextSteps.first()
        assertTrue(step.startsWith("The sessions were recorded with different capture settings"), step)
        assertTrue("source=UNPROCESSED vs source=VOICE_RECOGNITION" in step, step)
        assertEquals("Change is inconclusive at every position.", text.headline)
    }

    @Test
    fun tooFewClapsInEverySessionSayNotEnoughRatherThanInconclusive() {
        val (_, text) = compare(mapOf(500 to 0.7, 1000 to null, 2000 to 0.6), mapOf(500 to 0.5, 1000 to null, 2000 to 0.45))
        assertEquals("Not enough usable claps to compare the speech range yet.", text.headline)
        assertTrue(text.nextSteps.any { it.startsWith("Record more claps at Seat A") })
    }

    @Test
    fun aChangeWithinTheVariationIsInconclusive() {
        val (_, text) = compare(bands(null, 0.70, 0.70, 0.65, null), bands(null, 0.69, 0.69, 0.64, null))
        assertEquals(Verdict.INCONCLUSIVE, text.positions.single().verdict)
        assertEquals("Change is inconclusive at every position.", text.headline)
    }

    @Test
    fun theTreatmentIsComparedWithTheTarget() {
        val (_, text) = compare(bands(null, 0.78, 0.74, 0.66, null), bands(null, 0.6, 0.58, 0.56, null), target = BuiltInProfiles.BB93_SMALL_GROUP_ROOM)
        assertEquals(listOf("Seat A is now about 0.58 s, within UK school guidance for small group rooms (BB93, Table 6), at most 0.60 s."), text.target)
        assertEquals("bb93-small-group-room", text.profileId)
    }

    @Test
    fun suggestionsFollowTheConfirmedMapping() {
        fun ids(u: RoomUse) = TargetSuggestions.forUse(u).map { it.profile.id to (it.profile.target as ReverbTarget.UpperLimit).seconds }
        assertEquals(listOf("bb93-meeting-room" to 0.8, "bb93-small-group-room" to 0.6), ids(RoomUse.DINING_LIVING))
        assertEquals(listOf("bb93-meeting-room" to 0.8, "bb93-office" to 1.0), ids(RoomUse.HOME_OFFICE))
        assertEquals(
            listOf("bb93-meeting-room" to 0.8, "bb93-small-group-room" to 0.6, "bb93-kitchen" to 1.5), ids(RoomUse.KITCHEN),
            "the loosest value (school kitchens) comes last",
        )
        assertEquals(emptyList(), ids(RoomUse.OTHER))
        assertEquals("relaxed", TargetSuggestions.forUse(RoomUse.DINING_LIVING)[0].label)
        assertEquals("comfortable for group conversation", TargetSuggestions.forUse(RoomUse.DINING_LIVING)[1].label)
    }

    // ---- Wording ----

    /** Every text the rules can produce for the fixtures above, as serialised. */
    private fun everyText(): List<String> {
        val json = Json { encodeDefaults = true }
        val profiles = listOf(
            null, BuiltInProfiles.BB93_SMALL_GROUP_ROOM, BuiltInProfiles.BB93_KITCHEN,
            ReferenceProfile("u", 1, "Your target", source = ProfileSource.User(0), target = ReverbTarget.UpperLimit(0.5)),
            ReferenceProfile("i", 1, "Imp", source = ProfileSource.Imported("f.json", Citation("T", section = "S"), 0), target = ReverbTarget.TargetWithTolerance(0.6, 0.8, 1.2)),
            ReferenceProfile("v", 1, "Vol", source = ProfileSource.Imported("f.json", Citation("T", section = "S"), 0), target = ReverbTarget.VolumeFunction(0.5, -0.25, 20.0, 500.0, 0.0, 1.0)),
        )
        val shapes = listOf(
            bands(null, 0.78, 0.74, 0.66, 0.59), bands(0.5, 0.5, 0.55, 0.7, 0.75), bands(0.8, 0.8, 0.8, 0.8, 0.8),
            mapOf(500 to 0.7, 1000 to null, 2000 to 0.6), bands(null, 0.4, 0.4, 0.4, null),
        )
        val out = ArrayList<String>()
        for (p in profiles) for (v in listOf(null, 45.0)) for (shape in shapes) {
            val r = room(p, v, seatA, seatB)
            val attempts = claps("s1", seatA, shape) + claps("s1", seatB, shape.mapValues { it.value?.times(0.7) }) +
                clap("s1", seatA, shape, attemptReason = ReasonCode.ADDITIONAL_IMPULSE, staleFit = 0.87) +
                clap("s1", seatB, shape, attemptReason = ReasonCode.CLIPPING, staleFit = 0.87)
            out += json.encodeToString(SessionSummary.serializer(), summarizer.session(r, session("s1", SessionKind.BASELINE), attempts, version, p))
            for (repeat in listOf(null, shape)) {
                out += json.encodeToString(ComparisonText.serializer(), compare(shape, shape.mapValues { it.value?.times(0.75) }, repeat, target = p).second)
            }
        }
        out += json.encodeToString(ComparisonText.serializer(), compare(bands(null, 0.7, 0.7, 0.7, null), bands(null, 0.5, 0.5, 0.5, null), treatmentConfig = config.copy(encoding = "PCM_16BIT")).second)
        return out
    }

    @Test
    fun noGeneratedTextMakesConfidenceClaimsOrMentionsSpl() {
        val texts = everyText()
        assertTrue(texts.size > 50)
        for (t in texts) {
            val lower = t.lowercase()
            for (banned in listOf("significan", "confidence", "certain", "db spl", "edt", "probab", "proven", "guarantee")) {
                assertFalse(banned in lower, "“$banned” in: $t")
            }
            assertFalse("standard for your home" in lower)
        }
    }

    @Test
    fun noGeneratedTextShowsAValueForAnInvalidBand() {
        // Every invalid band in the fixtures carries a stale 0.87 s fit, and no valid value is 0.87.
        for (t in everyText()) assertFalse("0.87" in t, t)
    }
}
