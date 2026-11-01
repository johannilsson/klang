package se.klang.sessions

import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verdict, grouping and wording of [AttemptReporter], and the sound events of [RecordingTimeline]. Synthetic fixtures. */
class AttemptReportTest {
    private val version = AnalysisConfiguration().version
    private fun fit(s: Double) = Fit(s, -60 / s, 0.0, -5.0, -25.0, 0.05, 0.4, 0.99, 1.0, 0.1)
    private fun reason(code: ReasonCode, sev: QualityState, threshold: Double? = null) = Reason(code, sev, "analyser text for $code", null, threshold)

    private fun band(hz: Int, t20: Double?, margin: Double, reasons: List<Reason> = emptyList(), state: QualityState? = null, staleFit: Double? = null) = BandRecord(
        hz, hz == 250 || hz == 4000,
        state ?: if (t20 == null) QualityState.INVALID else QualityState.VALID, reasons,
        t20 = t20?.let(::fit) ?: staleFit?.let(::fit), marginDb = margin,
    )

    private fun record(
        bands: List<BandRecord>, peak: Double = -6.0, bg: Double = -66.0, onset: Double = 1.5,
        reasons: List<Reason> = emptyList(), state: QualityState = QualityState.VALID,
    ) = AnalysisRecord(
        "klang.schroeder-truncated", version, Records.configuration(AnalysisConfiguration()), 0, state, reasons, 48_000, 5.0, onset, peak, bg, bands,
    )

    private val snr = reason(ReasonCode.INSUFFICIENT_SNR_MARGIN, QualityState.INVALID, 35.0)
    private val range = reason(ReasonCode.INSUFFICIENT_DECAY_RANGE, QualityState.INVALID)

    private fun good() = record(
        listOf(band(250, null, 28.0, listOf(snr)), band(500, 0.78, 43.0), band(1000, 0.74, 47.0), band(2000, 0.66, 50.0), band(4000, 0.59, 52.0)),
    )

    private fun noisy() = record(
        listOf(250, 500, 1000, 2000, 4000).zip(listOf(22.9, 23.4, 23.6, 22.8, 24.4)).map { (hz, m) -> band(hz, null, m, listOf(snr, range)) },
        peak = -4.9, bg = -38.7,
    )

    private fun timeline(vararg events: SoundEvent) = RecordingTimeline(0.05, FloatArray(0), 5.0, 1.5, -8.0, -70.0, events.toList())

    private fun tap() = record(
        listOf(250, 500, 1000, 2000, 4000).map { hz ->
            band(hz, null, 50.0, listOf(reason(ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID)))
        },
        peak = -8.0, bg = -70.0, reasons = listOf(reason(ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID)), state = QualityState.INVALID,
    )

    private fun loud() = record(
        listOf(250, 500, 1000, 2000, 4000).map { hz ->
            band(hz, 0.7, 48.0, listOf(reason(ReasonCode.NEAR_FULL_SCALE, QualityState.QUESTIONABLE)), QualityState.QUESTIONABLE)
        },
        peak = -0.4, reasons = listOf(reason(ReasonCode.NEAR_FULL_SCALE, QualityState.QUESTIONABLE)), state = QualityState.QUESTIONABLE,
    )

    // ---- Verdicts ----

    @Test
    fun aGoodClapIsUsableAndTheMissingLowBandIsACautionNotAProblem() {
        val r = AttemptReporter.report(good())
        assertEquals(AttemptVerdict.USABLE, r.verdict)
        assertEquals("Usable clap", r.headline)
        assertEquals("Four of five bands could be measured.", r.summary)
        assertEquals("500 Hz 0.78 · 1k 0.74 · 2k 0.66 · 4k 0.59 s", r.t20Line)
        assertEquals(r.t20Line, r.oneLine)
        val low = r.findings.single { it.kind == FindingKind.CAUTION }
        assertEquals("250 Hz could not be measured.", low.title)
        assertTrue("(28 dB, needs 35)" in low.detail && "Low tones often are with a handclap." in low.detail, low.detail)
        assertNull(low.advice, "nothing to change for a band a handclap cannot reach")
        assertEquals(listOf(250), low.bandsHz)
        val ok = r.findings.filter { it.kind == FindingKind.OK }.map { it.title }
        assertEquals(listOf("Clap level is good.", "Nothing else was louder than the room.", "Clean recording."), ok)
        assertEquals(listOf("Clap −6.0 dBFS · good level", "Background −66.0 dBFS · quiet", "Clap at 1.5 s"), r.chips.map { it.text })
        assertTrue(r.chips.none { it.warn })
    }

    @Test
    fun aNoisyRoomIsOneProblemWithOneAdviceNotOnePerBand() {
        val r = AttemptReporter.report(noisy())
        assertEquals(AttemptVerdict.NOT_USABLE, r.verdict)
        assertEquals("Not usable: the room was too noisy", r.headline)
        assertEquals("The clap was loud and clear, but the background noise was too close to it in every band, so no band could be measured.", r.summary)
        val problem = r.findings.single { it.kind == FindingKind.PROBLEM }
        assertEquals("The background was too loud.", problem.title)
        assertEquals("In every band the clap stood only 22–24 dB above the noise, and 35 dB is needed.", problem.detail)
        assertEquals(ReasonTexts.advice(ReasonCode.INSUFFICIENT_SNR_MARGIN), problem.advice)
        assertEquals(1, r.findings.count { it.advice != null }, "the same advice is not repeated for each band or reason")
        assertEquals("Room too noisy: the clap was only 22–24 dB above the noise (needs 35).", r.oneLine)
        assertNull(r.t20Line)
        assertTrue(r.chips.any { it.text == "Background −38.7 dBFS · too high" && it.warn })
    }

    @Test
    fun aSoundBeforeTheClapIsNamedWithItsTimeAndALaterOneIsHarmless() {
        val tl = timeline(SoundEvent(0.6, -24.0, SoundEvent.Kind.BEFORE_CLAP), SoundEvent(3.2, -42.0, SoundEvent.Kind.AFTER_DECAY))
        val r = AttemptReporter.report(tap(), emptyList(), tl)
        assertEquals(AttemptVerdict.NOT_USABLE, r.verdict)
        assertEquals("Not usable: a sound came before the clap", r.headline)
        assertEquals("A tap or movement at 0.6 s was too loud to ignore, so the whole recording was rejected.", r.summary)
        val problem = r.findings.single { it.kind == FindingKind.PROBLEM }
        assertEquals("Another sound came before the clap.", problem.title)
        assertTrue(problem.detail.startsWith("At 0.6 s: a tap, movement or a second clap."), problem.detail)
        assertTrue("Clap only when it says" in problem.advice!!)
        assertEquals("Another sound at 0.6 s, before the clap.", r.oneLine)
        assertTrue(r.chips.first().let { it.text == "Other sound at 0.6 s · −24.0 dBFS" && it.warn })
        assertTrue(r.findings.any { it.kind == FindingKind.OK && it.title == "The later sound at 3.2 s does not matter." })
        assertTrue(r.findings.none { it.title == "Nothing else was louder than the room." })
        // Without a timeline the problem is still stated, just without a time.
        val bare = AttemptReporter.report(tap())
        assertEquals("Another sound disturbed the recording.", bare.findings.first { it.kind == FindingKind.PROBLEM }.title)
        assertEquals("Not usable: another sound disturbed it", bare.headline)
    }

    @Test
    fun aVeryLoudClapIsUsableWithCautionAndTheCautionLeadsTheRow() {
        val r = AttemptReporter.report(loud())
        assertEquals(AttemptVerdict.USABLE_WITH_CAUTION, r.verdict)
        assertEquals("Usable with caution", r.headline)
        assertEquals("It can be used. Note: The clap was very loud (−0.4 dBFS).", r.summary)
        val c = r.findings.single { it.kind == FindingKind.CAUTION }
        assertEquals("The microphone may have compressed it.", c.detail)
        assertEquals("Very loud (−0.4 dBFS): the microphone may have compressed it. 250 Hz 0.70 · 500 0.70 · 1k 0.70 · 2k 0.70 · 4k 0.70 s", r.oneLine)
        assertTrue(r.chips.any { it.text == "Clap −0.4 dBFS · very loud" && it.warn })
        assertTrue(r.findings.none { it.title == "Clap level is good." })
    }

    @Test
    fun aQuietClapIsAHintNotAVerdict() {
        val r = AttemptReporter.report(record(good().bands, peak = -20.0))
        assertEquals(AttemptVerdict.USABLE, r.verdict)
        assertTrue(r.findings.any { it.title == "The clap was quiet (−20.0 dBFS)." && it.advice == "Step closer or clap a little firmer." })
        assertTrue(r.chips.any { it.text == "Clap −20.0 dBFS · quiet" && it.warn })
    }

    @Test
    fun aRecordingWithoutAnAnalysisIsNotAnalysed() {
        val r = AttemptReporter.report(null)
        assertEquals(AttemptVerdict.NOT_ANALYSED, r.verdict)
        assertEquals("Not analysed yet.", r.oneLine)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun aMissingSpeechBandMakesItNotUsableEvenWithoutAReason() {
        val r = AttemptReporter.report(record(listOf(band(2000, 0.6, 50.0))))
        assertEquals(AttemptVerdict.NOT_USABLE, r.verdict)
        assertEquals("The speech range could not be measured.", r.findings.single { it.kind == FindingKind.PROBLEM }.title)
    }

    @Test
    fun aSpeechBandThatFailsAloneIsNamedWithItsAdviceAndTheOthersAreFine() {
        val bands = listOf(band(500, 0.78, 43.0), band(1000, null, 30.0, listOf(snr)), band(2000, 0.66, 50.0))
        val r = AttemptReporter.report(record(bands))
        assertEquals(AttemptVerdict.NOT_USABLE, r.verdict)
        val p = r.findings.single { it.kind == FindingKind.PROBLEM }
        assertEquals("1000 Hz could not be measured.", p.title)
        assertTrue("(30 dB, needs 35)" in p.detail)
        assertEquals("1000 Hz not measured: the clap was too weak there.", r.oneLine)
        assertEquals("500 Hz 0.78 · 2k 0.66 s", r.t20Line)
        assertEquals("Not usable: the room was too noisy", r.headline)
    }

    @Test
    fun irregularDecayInOneBandIsACautionWhenItIsInTheSpeechRange() {
        val bands = listOf(
            band(500, 0.78, 43.0), band(1000, 0.7, 47.0, listOf(reason(ReasonCode.NON_LINEAR_DECAY, QualityState.QUESTIONABLE)), QualityState.QUESTIONABLE),
            band(2000, 0.66, 50.0),
        )
        val r = AttemptReporter.report(record(bands))
        assertEquals(AttemptVerdict.USABLE_WITH_CAUTION, r.verdict)
        assertEquals("The decay was irregular at 1000 Hz.", r.findings.first { it.kind == FindingKind.CAUTION }.title)
    }

    @Test
    fun aTapThatRaisesTheMeasuredBackgroundIsTheProblemNotTheRoom() {
        // What the analyser really reports: the tap is in the background segment, so every band fails the margin too.
        val bands = listOf(250, 500, 1000, 2000, 4000).map { hz ->
            band(hz, null, 21.0, listOf(reason(ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID), snr, range))
        }
        val a = record(bands, peak = -11.8, bg = -44.8, reasons = listOf(reason(ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID)), state = QualityState.INVALID)
        val tl = RecordingTimeline(0.05, FloatArray(0), 5.0, 1.5, -11.8, -44.8, listOf(SoundEvent(0.6, -30.0, SoundEvent.Kind.BEFORE_CLAP)), quietDbfs = -74.5)
        val r = AttemptReporter.report(a, emptyList(), tl)
        assertEquals("Not usable: a sound came before the clap", r.headline)
        val problem = r.findings.single { it.kind == FindingKind.PROBLEM }
        assertEquals("Another sound came before the clap.", problem.title)
        assertTrue("raised the background" in problem.detail, problem.detail)
        assertTrue(r.findings.none { it.title == "The background was too loud." }, "the room was quiet")
        assertTrue(r.chips.any { it.text == "Background −74.5 dBFS · quiet" && !it.warn }, r.chips.toString())
        assertEquals("Another sound at 0.6 s, before the clap.", r.oneLine)
        // The same failures without a sound in the quiet part are a noisy room, as before.
        assertEquals("The background was too loud.", AttemptReporter.report(noisy()).findings.first { it.kind == FindingKind.PROBLEM }.title)
    }

    @Test
    fun aQuietClapIsNotFlaggedWhenEveryBandHadRoomToSpare() {
        val fine = AttemptReporter.report(record(listOf(band(500, 0.78, 50.0), band(1000, 0.74, 52.0), band(2000, 0.66, 50.0)), peak = -14.0))
        assertEquals(AttemptVerdict.USABLE, fine.verdict)
        assertTrue(fine.findings.none { it.title.startsWith("The clap was quiet") })
        assertTrue(fine.chips.any { it.text == "Clap −14.0 dBFS" && !it.warn }, fine.chips.toString())
    }

    // ---- Wording ----

    private fun everyReport(): List<AttemptReport> {
        val stale = record(listOf(band(500, null, 20.0, listOf(snr), staleFit = 0.87), band(1000, 0.7, 50.0), band(2000, 0.6, 50.0)))
        val tl = timeline(SoundEvent(0.6, -24.0, SoundEvent.Kind.BEFORE_CLAP), SoundEvent(3.2, -42.0, SoundEvent.Kind.AFTER_DECAY))
        val codes = ReasonCode.entries.map { c ->
            record(
                listOf(band(500, null, 30.0, listOf(reason(c, QualityState.INVALID)), staleFit = 0.87), band(1000, null, 30.0, listOf(reason(c, QualityState.INVALID))), band(2000, 0.6, 50.0)),
                reasons = listOf(reason(c, QualityState.INVALID)),
            )
        }
        val questionable = ReasonCode.entries.map { c ->
            record(listOf(band(500, 0.7, 40.0, listOf(reason(c, QualityState.QUESTIONABLE)), QualityState.QUESTIONABLE), band(1000, 0.7, 40.0), band(2000, 0.6, 50.0)))
        }
        return (listOf(good(), noisy(), tap(), loud(), stale) + codes + questionable).flatMap { a -> listOf(AttemptReporter.report(a), AttemptReporter.report(a, listOf(CaptureEvent("OVERRUN", 5)), tl)) } +
            AttemptReporter.report(null)
    }

    @Test
    fun noReportMakesConfidenceClaimsMentionsSplOrEdtOrShowsAnInvalidBandsValue() {
        val reports = everyReport()
        assertTrue(reports.size > 100)
        for (r in reports) {
            val text = listOf(
                r.headline, r.summary, r.oneLine, r.t20Line.orEmpty(), r.chips.joinToString { it.text },
                r.findings.joinToString { "${it.title} ${it.detail} ${it.short} ${it.advice.orEmpty()}" },
            ).joinToString(" ").lowercase()
            for (banned in listOf("significan", "confidence", "certain", "db spl", "edt", "probab", "guarantee")) assertFalse(banned in text, "“$banned” in: $text")
            assertFalse("0.87" in text, "the stale fit of an invalid band leaked: $text")
        }
    }

    @Test
    fun theSameCauseIsNeverAdvisedTwice() {
        for (r in everyReport()) {
            val advice = r.findings.mapNotNull { it.advice }
            assertEquals(advice.distinct(), advice, "repeated advice in ${r.headline}")
        }
    }

    // ---- RecordingTimeline ----

    private fun synth(clapAt: Double = 1.5, tapAt: Double? = null, laterAt: Double? = null, rate: Int = 8000): FloatArray {
        val rnd = Random(4)
        val k = 3.0 * ln(10.0) / 0.5
        return FloatArray(5 * rate) { i ->
            val t = i.toDouble() / rate
            var v = 1e-3 * rnd.nextGaussian() // −60 dBFS room
            if (t >= clapAt) v += 0.3 * exp(-k * (t - clapAt)) * rnd.nextGaussian()
            if (tapAt != null && t in tapAt..(tapAt + 0.04)) v += 0.05 * rnd.nextGaussian()
            if (laterAt != null && t in laterAt..(laterAt + 0.04)) v += 0.01 * rnd.nextGaussian()
            v.toFloat()
        }
    }

    private val analysis = record(emptyList(), peak = -10.0, bg = -60.0)

    @Test
    fun theTimelineFindsASoundBeforeTheClapAndAHarmlessOneAfterTheDecay() {
        val tl = RecordingTimeline.of(synth(tapAt = 0.6, laterAt = 3.2), 8000, analysis)
        assertEquals(100, tl.levelsDbfs.size)
        assertEquals(0.05, tl.stepSeconds, 0.0)
        assertEquals(1.5, tl.onsetSeconds!!, 0.0)
        assertEquals(-10.0, tl.peakDbfs, 0.0)
        assertEquals(-60.0, tl.backgroundDbfs!!, 0.0)
        val before = tl.events.single { it.kind == SoundEvent.Kind.BEFORE_CLAP }
        assertEquals(0.6, before.timeSeconds, 0.1)
        assertTrue(before.levelDbfs > -45.0, "the tap is far above the −60 dBFS room: ${before.levelDbfs}")
        val after = tl.events.single { it.kind == SoundEvent.Kind.AFTER_DECAY }
        assertEquals(3.2, after.timeSeconds, 0.1)
    }

    @Test
    fun aCleanRecordingHasNoEventsAndTheClapIsTheLoudestWindow() {
        val tl = RecordingTimeline.of(synth(), 8000, analysis)
        assertTrue(tl.events.isEmpty(), tl.events.toString())
        val loudest = tl.levelsDbfs.indices.maxBy { tl.levelsDbfs[it] }
        assertEquals(1.5, loudest * tl.stepSeconds, 0.1)
    }

    @Test
    fun withoutAnAnalysisThereIsNoOnsetAndNoEventsAndSilenceIsFloored() {
        val tl = RecordingTimeline.of(synth(tapAt = 0.6), 8000, null)
        assertNull(tl.onsetSeconds)
        assertTrue(tl.events.isEmpty(), "events need the onset from the analysis")
        // A background recording has no clap: its typical level is the median window.
        assertEquals(-60.0, tl.backgroundDbfs!!, 1.5)
        assertEquals(-60.0, tl.quietDbfs!!, 1.5)
        val silent = RecordingTimeline.of(FloatArray(8000 * 2), 8000, null)
        assertTrue(silent.levelsDbfs.all { it == -100f })
    }
}
