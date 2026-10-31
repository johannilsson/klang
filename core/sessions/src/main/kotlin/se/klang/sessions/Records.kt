package se.klang.sessions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.AttemptAnalysis
import se.klang.dsp.BandResult
import se.klang.dsp.DecayCurve
import se.klang.dsp.DecayFit
import se.klang.dsp.QualityReason

/** Conversion from pure DSP results to persisted records. */
object Records {
    fun of(a: AttemptAnalysis, analyzedAtEpochMs: Long): AnalysisRecord = AnalysisRecord(
        algorithmId = a.configuration.algorithmId,
        algorithmVersion = a.configuration.version,
        configuration = configuration(a.configuration),
        analyzedAtEpochMs = analyzedAtEpochMs,
        state = a.state,
        reasons = a.reasons.map(::of),
        sampleRate = a.sampleRate,
        durationSeconds = a.durationSeconds,
        onsetSeconds = a.onsetSeconds,
        peakDbfs = a.peakDbfs,
        backgroundRmsDbfs = a.backgroundRmsDbfs,
        bands = a.bands.map(::of),
    )

    fun of(r: QualityReason) = Reason(r.code, r.severity, r.message, r.value?.finiteOrNull(), r.threshold?.finiteOrNull())

    fun of(b: BandResult) = BandRecord(
        centerHz = b.centerHz,
        extended = b.extended,
        state = b.state,
        reasons = b.reasons.map(::of),
        t20 = b.t20?.takeIf { it.seconds.isFinite() }?.let(::of),
        edt = b.edt?.takeIf { it.seconds.isFinite() }?.let(::of),
        t30Diagnostic = b.t30?.takeIf { it.seconds.isFinite() }?.let(::of),
        marginDb = b.marginDb?.finiteOrNull(),
        peakLevelDbfs = b.peakLevelDbfs?.finiteOrNull(),
        backgroundLevelDbfs = b.backgroundLevelDbfs?.finiteOrNull(),
        truncationSeconds = b.truncationSeconds?.finiteOrNull(),
        compensationDb = b.compensationDb?.finiteOrNull(),
        curve = b.curve?.let(::of),
    )

    fun of(f: DecayFit) = Fit(
        f.seconds, f.slopeDbPerSecond, f.interceptDb, f.rangeStartDb, f.rangeEndDb,
        f.startSeconds, f.endSeconds, f.rSquared, f.nonLinearityPermille, f.residualRmsDb,
    )

    fun of(c: DecayCurve) = Curve(c.stepSeconds, c.edcDb.toList(), c.envelopeDb.toList(), c.backgroundDb)

    /** Every field of the configuration, so an exported result is self-describing. */
    fun configuration(c: AnalysisConfiguration): JsonObject = buildJsonObject {
        put("algorithmId", c.algorithmId)
        put("version", c.version)
        putJsonArray("bandsHz") { c.bandsHz.forEach { add(it) } }
        putJsonArray("primaryBandsHz") { c.primaryBandsHz.sorted().forEach { add(it) } }
        put("filter", "Butterworth band-pass, prototype order ${c.filterOrder} (band-pass order ${2 * c.filterOrder}), base-2 octave edges, bilinear with pre-warping")
        put("filterOrder", c.filterOrder)
        put("filterDirection", c.filterDirection.name)
        put("minPreRollSeconds", c.minPreRollSeconds)
        put("filterSettleSeconds", c.filterSettleSeconds)
        put("onsetGuardSeconds", c.onsetGuardSeconds)
        put("minPostOnsetSeconds", c.minPostOnsetSeconds)
        put("onsetThresholdDb", c.onsetThresholdDb)
        put("onsetSearchSeconds", c.onsetSearchSeconds)
        put("onsetAboveBackgroundDb", c.onsetAboveBackgroundDb)
        put("clipLevel", c.clipLevel)
        put("nearFullScaleLevel", c.nearFullScaleLevel)
        put("clipRunInvalid", c.clipRunInvalid)
        put("flatTopRun", c.flatTopRun)
        put("flatTopMinLevel", c.flatTopMinLevel)
        put("dropoutZeroRunSeconds", c.dropoutZeroRunSeconds)
        put("contaminationWindowSeconds", c.contaminationWindowSeconds)
        put("secondImpulseHoldoffSeconds", c.secondImpulseHoldoffSeconds)
        put("secondImpulseRiseDb", c.secondImpulseRiseDb)
        put("secondImpulseLookbackSeconds", c.secondImpulseLookbackSeconds)
        put("secondImpulseAboveNoiseDb", c.secondImpulseAboveNoiseDb)
        put("lateEventGuardSeconds", c.lateEventGuardSeconds)
        put("preOnsetImpulseWithinDb", c.preOnsetImpulseWithinDb)
        put("backgroundTransientDb", c.backgroundTransientDb)
        put("minMarginT20Db", c.minMarginT20Db)
        put("minMarginEdtDb", c.minMarginEdtDb)
        put("minMarginT30Db", c.minMarginT30Db)
        put("minEnvelopeWindowSeconds", c.minEnvelopeWindowSeconds)
        put("envelopeCycles", c.envelopeCycles)
        put("truncationHeadroomDb", c.truncationHeadroomDb)
        put("truncationFitSpanDb", c.truncationFitSpanDb)
        put("truncationMaxIterations", c.truncationMaxIterations)
        put("minEdcHeadroomBelowFitDb", c.minEdcHeadroomBelowFitDb)
        put("tailNoiseMismatchDb", c.tailNoiseMismatchDb)
        put("maxNonLinearityPermille", c.maxNonLinearityPermille)
        put("maxCurvaturePercent", c.maxCurvaturePercent)
        put("minDecaySeconds", c.minDecaySeconds)
        put("maxDecaySeconds", c.maxDecaySeconds)
        put("minBandwidthTimeProduct", c.minBandwidthTimeProduct)
        put("curveStepSeconds", c.curveStepSeconds)
    }

    private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }
}
