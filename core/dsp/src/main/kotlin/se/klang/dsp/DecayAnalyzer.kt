package se.klang.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Estimates octave-band decay times from a single recorded impulse (e.g. one handclap).
 *
 * Pipeline (see docs/dsp.md for the full description and rationale):
 *  1. Integrity checks on the raw samples: clipping, flat tops, dropouts, capture discontinuities.
 *  2. Impulse detection: global peak, onset on the leading edge, pre-roll/tail length checks.
 *  3. Broadband contamination checks: separate impulses before/after the main one, background transients.
 *  4. Per octave band: Butterworth band-pass, squared signal, background power from the pre-onset
 *     segment, short-time envelope, iterative truncation point where the decay meets the background,
 *     Schroeder backward integral truncated there plus an exponential tail correction, 10·log10,
 *     line fits for T20 (−5…−25 dB), EDT (0…−10 dB) and diagnostic T30 (−5…−35 dB).
 *  5. Per-band validity gates with explicit reasons.
 *
 * Deterministic and platform-independent: same input and configuration give the same output.
 */
class DecayAnalyzer(val config: AnalysisConfiguration = AnalysisConfiguration()) {

    fun analyze(signal: PcmSignal): AttemptAnalysis {
        val fs = signal.sampleRate
        val x = signal.samples
        val n = x.size
        val reasons = ArrayList<QualityReason>()
        val peakDbfs = Levels.peakDbfs(x)

        checkIntegrity(signal, reasons)

        // ---- Impulse detection ----
        var peakIdx = -1
        var peakAbs = 0.0
        for (i in 0 until n) {
            val a = abs(x[i].toDouble())
            if (a > peakAbs) { peakAbs = a; peakIdx = i }
        }
        if (peakIdx < 0 || peakAbs == 0.0) {
            reasons += QualityReason(ReasonCode.NO_IMPULSE_FOUND, QualityState.INVALID, "Recording is silent")
            return earlyExit(signal, reasons, peakDbfs, null, null)
        }
        // Onset threshold: peak - 20 dB, but never within 15 dB of the early background, so background
        // noise samples in a noisy room cannot be mistaken for the start of the clap.
        val settleSamples = (config.filterSettleSeconds * fs).toInt()
        val earlyEnd = settleSamples + (config.minPreRollSeconds * fs).toInt()
        val earlyRms = if (earlyEnd < peakIdx - (config.onsetGuardSeconds * fs).toInt()) {
            Math.pow(10.0, Levels.rmsDbfs(x, settleSamples, earlyEnd) / 20.0)
        } else 0.0
        val onsetLevel = minOf(
            peakAbs,
            max(peakAbs * Math.pow(10.0, config.onsetThresholdDb / 20.0), earlyRms * Math.pow(10.0, config.onsetAboveBackgroundDb / 20.0)),
        )
        val searchFrom = max(settleSamples, peakIdx - (config.onsetSearchSeconds * fs).toInt())
        var onset = peakIdx
        for (i in searchFrom..peakIdx) {
            if (abs(x[i].toDouble()) >= onsetLevel) { onset = i; break }
        }
        val onsetSeconds = onset.toDouble() / fs

        val settle = (config.filterSettleSeconds * fs).toInt()
        val bgStart = settle
        val bgEnd = onset - (config.onsetGuardSeconds * fs).toInt()
        if (bgEnd - bgStart < config.minPreRollSeconds * fs) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_PRE_ROLL, QualityState.INVALID,
                "Need at least ${config.minPreRollSeconds} s of background before the impulse",
                value = max(0, bgEnd - bgStart).toDouble() / fs, threshold = config.minPreRollSeconds,
            )
        }
        if ((n - onset) < config.minPostOnsetSeconds * fs) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_TAIL, QualityState.INVALID,
                "Need at least ${config.minPostOnsetSeconds} s after the impulse for the decay and noise tail",
                value = (n - onset).toDouble() / fs, threshold = config.minPostOnsetSeconds,
            )
        }
        if (reasons.any { it.code == ReasonCode.INSUFFICIENT_PRE_ROLL || it.code == ReasonCode.INSUFFICIENT_TAIL }) {
            return earlyExit(signal, reasons, peakDbfs, onsetSeconds, null)
        }
        val backgroundRmsDbfs = Levels.rmsDbfs(x, bgStart, bgEnd)
        if (peakDbfs - backgroundRmsDbfs < 20.0) {
            reasons += QualityReason(
                ReasonCode.NO_IMPULSE_FOUND, QualityState.INVALID,
                "No impulse clearly above the background (peak only ${fmt(peakDbfs - backgroundRmsDbfs)} dB above background RMS)",
                value = peakDbfs - backgroundRmsDbfs, threshold = 20.0,
            )
            return earlyExit(signal, reasons, peakDbfs, onsetSeconds, backgroundRmsDbfs)
        }

        checkContamination(x, fs, onset, peakIdx, bgStart, bgEnd, reasons)

        val attemptState = reasons.worstState()
        val bands = config.bandsHz.map { fc ->
            analyzeBand(signal, fc, onset, bgStart, bgEnd, reasons, attemptState)
        }
        return AttemptAnalysis(
            configuration = config,
            sampleRate = fs,
            durationSeconds = signal.durationSeconds,
            state = attemptState,
            reasons = reasons,
            onsetSeconds = onsetSeconds,
            peakDbfs = peakDbfs,
            backgroundRmsDbfs = backgroundRmsDbfs,
            bands = bands,
        )
    }

    // ------------------------------------------------------------------ integrity

    private fun checkIntegrity(signal: PcmSignal, reasons: MutableList<QualityReason>) {
        val x = signal.samples
        var clipped = 0
        var clipRun = 0
        var maxClipRun = 0
        var flatRun = 1
        var maxFlatRun = 1
        var zeroRun = 0
        var maxZeroRun = 0
        var nonZero = false
        for (i in x.indices) {
            val v = x[i]
            val a = abs(v.toDouble())
            if (a >= config.clipLevel) { clipped++; clipRun++; maxClipRun = max(maxClipRun, clipRun) } else clipRun = 0
            if (i > 0 && v == x[i - 1] && a >= config.flatTopMinLevel) { flatRun++; maxFlatRun = max(maxFlatRun, flatRun) } else flatRun = 1
            if (v == 0f) { zeroRun++; maxZeroRun = max(maxZeroRun, zeroRun) } else { zeroRun = 0; nonZero = true }
        }
        if (maxClipRun >= config.clipRunInvalid) {
            reasons += QualityReason(
                ReasonCode.CLIPPING, QualityState.INVALID,
                "Clipped: $clipped samples at digital full scale (longest run $maxClipRun). Clap less hard or move the source further away.",
                value = maxClipRun.toDouble(), threshold = config.clipRunInvalid.toDouble(),
            )
        } else if (clipped > 0) {
            reasons += QualityReason(
                ReasonCode.FULL_SCALE_SAMPLE, QualityState.QUESTIONABLE,
                "$clipped isolated sample(s) at digital full scale; clipping cannot be ruled out",
                value = clipped.toDouble(),
            )
        }
        if (maxFlatRun >= config.flatTopRun) {
            reasons += QualityReason(
                ReasonCode.FLAT_TOP, QualityState.INVALID,
                "Flat-topped waveform ($maxFlatRun identical high-level samples): likely clipping or limiting before digitisation",
                value = maxFlatRun.toDouble(), threshold = config.flatTopRun.toDouble(),
            )
        }
        val dropoutSamples = ceil(config.dropoutZeroRunSeconds * signal.sampleRate).toInt()
        if (nonZero && maxZeroRun >= dropoutSamples) {
            reasons += QualityReason(
                ReasonCode.DROPOUT, QualityState.INVALID,
                "Run of ${maxZeroRun} exact-zero samples: capture dropout or a device noise gate",
                value = maxZeroRun.toDouble() / signal.sampleRate, threshold = config.dropoutZeroRunSeconds,
            )
        }
        if (signal.discontinuities.isNotEmpty()) {
            reasons += QualityReason(
                ReasonCode.CAPTURE_DISCONTINUITY, QualityState.INVALID,
                "Capture reported ${signal.discontinuities.size} timing discontinuity(ies) (overrun, short read or route change)",
                value = signal.discontinuities.size.toDouble(),
            )
        }
    }

    // ------------------------------------------------------------------ contamination

    private fun checkContamination(
        x: FloatArray, fs: Int, onset: Int, peakIdx: Int, bgStart: Int, bgEnd: Int,
        reasons: MutableList<QualityReason>,
    ) {
        val w = max(1, (config.contaminationWindowSeconds * fs).roundToInt())
        fun windowDb(start: Int): Double {
            var acc = 0.0
            val end = min(x.size, start + w)
            for (i in start until end) { val v = x[i].toDouble(); acc += v * v }
            return Levels.powerToDb(acc / max(1, end - start))
        }
        val peakWindowDb = windowDb(max(0, peakIdx - w / 2))
        val bgLevels = ArrayList<Double>()
        var s = bgStart
        while (s + w <= bgEnd) { bgLevels += windowDb(s); s += w }
        val bgMedian = bgLevels.sorted().let { if (it.isEmpty()) Levels.FLOOR_DB else it[it.size / 2] }
        val bgMax = bgLevels.maxOrNull() ?: Levels.FLOOR_DB

        val bgTransient = bgMax >= bgMedian + config.backgroundTransientDb
        if (bgTransient && bgMax >= peakWindowDb - config.preOnsetImpulseWithinDb) {
            reasons += QualityReason(
                ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID,
                "Another loud event before the clap (within ${fmt(peakWindowDb - bgMax)} dB of it)",
                value = peakWindowDb - bgMax, threshold = config.preOnsetImpulseWithinDb,
            )
        } else if (bgTransient) {
            reasons += QualityReason(
                ReasonCode.BACKGROUND_TRANSIENT, QualityState.QUESTIONABLE,
                "Transient in the background segment ${fmt(bgMax - bgMedian)} dB above the typical background",
                value = bgMax - bgMedian, threshold = config.backgroundTransientDb,
            )
        }

        // After the onset: a window that jumps above everything in the preceding look-back interval.
        // Comparing against the recent maximum (not the running minimum) keeps beating between
        // modes, which dips and recovers within its own decaying envelope, from looking like a new event.
        val lookback = max(1, (config.secondImpulseLookbackSeconds * fs / w).roundToInt())
        // Starts at the onset (not the peak) so a louder second clap before the peak is caught too.
        val first = onset + (config.secondImpulseHoldoffSeconds * fs).toInt()
        val levels = ArrayList<Double>()
        var start = max(0, first - lookback * w)
        while (start + w <= x.size) { levels += windowDb(start); start += w }
        val firstK = (first - max(0, first - lookback * w)) / w
        for (k in firstK until levels.size) {
            var recentMax = Levels.FLOOR_DB
            for (j in max(0, k - lookback) until k) recentMax = max(recentMax, levels[j])
            val l = levels[k]
            if (l >= recentMax + config.secondImpulseRiseDb && l >= bgMedian + config.secondImpulseAboveNoiseDb) {
                val at = max(0, first - lookback * w) + k * w
                reasons += QualityReason(
                    ReasonCode.ADDITIONAL_IMPULSE, QualityState.INVALID,
                    "Second sound event ${fmt((at - onset).toDouble() / fs)} s after the clap; let the decay finish and keep the room quiet",
                    value = (at - onset).toDouble() / fs,
                )
                return
            }
        }
    }

    // ------------------------------------------------------------------ bands

    private fun analyzeBand(
        signal: PcmSignal, fc: Int, onset: Int, bgStart: Int, bgEnd: Int,
        attemptReasons: List<QualityReason>, attemptState: QualityState,
    ): BandResult {
        val fs = signal.sampleRate
        val extended = fc !in config.primaryBandsHz
        val reasons = ArrayList<QualityReason>()
        // Attempt-level problems apply to every band.
        attemptReasons.forEach { reasons += it }

        if (fc * Math.sqrt(2.0) >= 0.45 * fs) {
            reasons += QualityReason(ReasonCode.SAMPLE_RATE_TOO_LOW, QualityState.INVALID, "Sample rate $fs Hz too low for the $fc Hz band")
            return emptyBand(fc, extended, reasons)
        }
        val filter = OctaveFilter(fc.toDouble(), fs, config.filterOrder)
        val y = filter.process(signal.samples, config.filterDirection)
        val n = y.size
        val p = DoubleArray(n) { y[it] * y[it] }

        // Background power: mean square of the band signal over the pre-onset segment.
        var bgAcc = 0.0
        for (i in bgStart until bgEnd) bgAcc += p[i]
        val noisePow = max(bgAcc / (bgEnd - bgStart), 1e-30)

        // Short-time envelope from onset, non-overlapping windows.
        val wn = max(1, (max(config.minEnvelopeWindowSeconds, config.envelopeCycles / fc) * fs).roundToInt())
        val tailEnd = n - (config.filterSettleSeconds * fs).toInt() // excludes the reverse-filter start-up
        val nWin = (tailEnd - onset) / wn
        val env = DoubleArray(nWin)
        val envT = DoubleArray(nWin)
        for (k in 0 until nWin) {
            var a = 0.0
            val s = onset + k * wn
            for (i in s until s + wn) a += p[i]
            env[k] = a / wn
            envT[k] = (k + 0.5) * wn / fs
        }
        var kPeak = 0
        for (k in 1 until nWin) if (env[k] > env[kPeak]) kPeak = k
        val peakPow = env[kPeak]
        val marginDb = 10.0 * log10(peakPow / noisePow)
        val envDb = DoubleArray(nWin) { 10.0 * log10(max(env[it], 1e-30) / peakPow) }
        val noiseDb = -marginDb

        val peakLevelDbfs = Levels.powerToDb(peakPow)
        val backgroundLevelDbfs = Levels.powerToDb(noisePow)
        if (marginDb < config.minMarginT20Db) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_SNR_MARGIN, QualityState.INVALID,
                "Only ${fmt(marginDb)} dB between clap and background in this band; T20 needs ${fmt(config.minMarginT20Db)} dB",
                value = marginDb, threshold = config.minMarginT20Db,
            )
        }

        // ---- Truncation point (iterative, Lundeby-style, using the measured pre-onset background) ----
        val headroomDb = noiseDb + config.truncationHeadroomDb
        var kStop = kPeak + 1
        while (kStop < nWin && envDb[kStop] >= headroomDb) kStop++
        var line = fitLine(envT, envDb, kPeak, kStop)
        if (line == null || line.slope >= 0.0) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_DECAY_RANGE, QualityState.INVALID,
                "Band decay does not rise clearly above the background",
                value = marginDb, threshold = config.minMarginT20Db,
            )
            return emptyBand(fc, extended, reasons, marginDb, peakLevelDbfs, backgroundLevelDbfs)
        }
        var tc = (noiseDb - line.intercept) / line.slope
        var converged = false
        repeat(config.truncationMaxIterations) {
            if (converged) return@repeat
            val l = line!!
            val tUpper = (headroomDb + config.truncationFitSpanDb - l.intercept) / l.slope
            val tLower = (headroomDb - l.intercept) / l.slope
            val from = max(kPeak, envT.indexOfFirst { it >= tUpper }.let { if (it < 0) nWin else it })
            val to = envT.indexOfFirst { it > tLower }.let { if (it < 0) nWin else it }
            val next = fitLine(envT, envDb, from, to)
            if (next == null || next.slope >= 0.0) { converged = true; return@repeat }
            val tcNext = (noiseDb - next.intercept) / next.slope
            line = next
            if (abs(tcNext - tc) < wn.toDouble() / fs) converged = true
            tc = tcNext
        }
        if (!converged) {
            reasons += QualityReason(
                ReasonCode.TRUNCATION_NOT_CONVERGED, QualityState.QUESTIONABLE,
                "Point where the decay meets the background did not converge",
            )
        }
        val lateLine = line!!
        val lateSlope = lateLine.slope // dB/s, negative
        val maxTc = (tailEnd - onset).toDouble() / fs
        if (tc > maxTc) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_TAIL, QualityState.INVALID,
                "Recording ends before this band's decay reaches the background",
                value = maxTc, threshold = tc,
            )
        }
        tc = tc.coerceIn(wn.toDouble() / fs, maxTc)
        val nc = onset + max(1, (tc * fs).toInt())

        // Tail consistency: background after the decay should match the pre-onset background.
        val tailFrom = nc + (10.0 / -lateSlope * fs).toInt()
        if (tailEnd - tailFrom >= (0.1 * fs).toInt()) {
            var acc = 0.0
            for (i in tailFrom until tailEnd) acc += p[i]
            val tailDb = 10.0 * log10(max(acc / (tailEnd - tailFrom), 1e-30) / noisePow)
            if (tailDb > config.tailNoiseMismatchDb) {
                reasons += QualityReason(
                    ReasonCode.TAIL_NOISE_MISMATCH, QualityState.QUESTIONABLE,
                    "Level after the decay is ${fmt(tailDb)} dB above the pre-clap background (non-stationary noise or a longer decay)",
                    value = tailDb, threshold = config.tailNoiseMismatchDb,
                )
            }
        }

        // ---- Schroeder backward integral, truncated at tc, with exponential tail correction ----
        // Energy after tc is modelled as the continuation of the late-decay line:
        // E_tail = P(tc) / k, with k = -slope·ln(10)/10 per sample and P(tc) the line's power at tc.
        // Normally the line meets the background at tc, so P(tc) equals the background power.
        // No noise subtraction is done, so the integrand never goes negative.
        val kPerSample = -lateSlope * ln(10.0) / 10.0 / fs
        val compensation = peakPow * Math.pow(10.0, lateLine.at(tc) / 10.0) / kPerSample
        val len = nc - onset
        val edc = DoubleArray(len)
        var acc = compensation
        for (i in len - 1 downTo 0) { acc += p[onset + i]; edc[i] = acc }
        val total = edc[0]
        val edcDb = DoubleArray(len) { 10.0 * log10(edc[it] / total) }
        val edcT = DoubleArray(len) { it.toDouble() / fs }
        val compensationDb = 10.0 * log10(compensation / total)

        val curve = buildCurve(p, onset, tailEnd, fs, edcDb, peakPow, noiseDb)

        // ---- Fits ----
        val t20Fit = fitDecay(edcT, edcDb, -5.0, -25.0)
        val edtFit = if (marginDb >= config.minMarginEdtDb) fitDecay(edcT, edcDb, 0.0, -10.0) else null
        val t30Fit = if (marginDb >= config.minMarginT30Db) fitDecay(edcT, edcDb, -5.0, -35.0) else null

        if (t20Fit == null) {
            reasons += QualityReason(
                ReasonCode.INSUFFICIENT_DECAY_RANGE, QualityState.INVALID,
                "Energy decay does not reach −25 dB (with ${fmt(config.minEdcHeadroomBelowFitDb)} dB to spare) before the background",
                value = edcDb.last(), threshold = -25.0 - config.minEdcHeadroomBelowFitDb,
            )
        } else {
            if (t20Fit.slopeDbPerSecond >= 0.0) {
                reasons += QualityReason(ReasonCode.NON_PHYSICAL_SLOPE, QualityState.INVALID, "Fitted decay slope is not negative")
            } else if (t20Fit.seconds !in config.minDecaySeconds..config.maxDecaySeconds) {
                reasons += QualityReason(
                    ReasonCode.DECAY_OUT_OF_RANGE, QualityState.INVALID,
                    "T20 of ${fmt(t20Fit.seconds)} s is outside the plausible ${config.minDecaySeconds}–${config.maxDecaySeconds} s range",
                    value = t20Fit.seconds,
                )
            }
            if (t20Fit.nonLinearityPermille > config.maxNonLinearityPermille) {
                reasons += QualityReason(
                    ReasonCode.NON_LINEAR_DECAY, QualityState.QUESTIONABLE,
                    "Decay is not straight over −5…−25 dB (ξ = ${fmt(t20Fit.nonLinearityPermille)} ‰)",
                    value = t20Fit.nonLinearityPermille, threshold = config.maxNonLinearityPermille,
                )
            }
            if (t30Fit != null && t20Fit.seconds > 0) {
                val curvature = 100.0 * (t30Fit.seconds / t20Fit.seconds - 1.0)
                if (abs(curvature) > config.maxCurvaturePercent) {
                    reasons += QualityReason(
                        ReasonCode.CURVED_DECAY, QualityState.QUESTIONABLE,
                        "Decay curvature ${fmt(curvature)} % between T20 and T30 (double slope?)",
                        value = curvature, threshold = config.maxCurvaturePercent,
                    )
                }
            }
            if (config.filterDirection == FilterDirection.FORWARD && filter.bandwidthHz * t20Fit.seconds < config.minBandwidthTimeProduct) {
                reasons += QualityReason(
                    ReasonCode.FILTER_RINGING_LIMIT, QualityState.QUESTIONABLE,
                    "Decay is short relative to the band filter's own ringing (B·T = ${fmt(filter.bandwidthHz * t20Fit.seconds)})",
                    value = filter.bandwidthHz * t20Fit.seconds, threshold = config.minBandwidthTimeProduct,
                )
            }
        }

        val state = reasons.worstState()
        val usable = state != QualityState.INVALID
        return BandResult(
            centerHz = fc,
            extended = extended,
            state = state,
            reasons = reasons,
            t20 = if (usable) t20Fit else null,
            edt = if (attemptState != QualityState.INVALID) edtFit?.takeIf { it.slopeDbPerSecond < 0 } else null,
            t30 = if (usable) t30Fit else null,
            marginDb = marginDb,
            peakLevelDbfs = peakLevelDbfs,
            backgroundLevelDbfs = backgroundLevelDbfs,
            truncationSeconds = tc,
            compensationDb = compensationDb,
            curve = curve,
        )
    }

    /**
     * Least-squares fit to the EDC between the first crossings of [startDb] and [endDb].
     * Returns null if the EDC does not reach [endDb] with [AnalysisConfiguration.minEdcHeadroomBelowFitDb]
     * to spare before the truncation point, so the fit never touches the model-dominated end.
     */
    private fun fitDecay(t: DoubleArray, edcDb: DoubleArray, startDb: Double, endDb: Double): DecayFit? {
        if (edcDb.last() > endDb - config.minEdcHeadroomBelowFitDb) return null
        val i1 = edcDb.indexOfFirst { it <= startDb }
        val i2 = edcDb.indexOfFirst { it <= endDb }
        if (i1 < 0 || i2 < 0 || i2 - i1 < 3) return null
        val stride = max(1, (i2 - i1) / 4000)
        val f = fitLine(t, edcDb, i1, i2 + 1, stride) ?: return null
        return DecayFit(
            seconds = if (f.slope < 0) -60.0 / f.slope else Double.POSITIVE_INFINITY,
            slopeDbPerSecond = f.slope,
            interceptDb = f.intercept,
            rangeStartDb = startDb,
            rangeEndDb = endDb,
            startSeconds = t[i1],
            endSeconds = t[i2],
            rSquared = f.rSquared,
            nonLinearityPermille = 1000.0 * (1.0 - f.rSquared),
            residualRmsDb = f.residualRms,
        )
    }

    private fun buildCurve(
        p: DoubleArray, onset: Int, tailEnd: Int, fs: Int, edcDb: DoubleArray, peakPow: Double, noiseDb: Double,
    ): DecayCurve {
        val step = max(1, (config.curveStepSeconds * fs).roundToInt())
        val edcOut = FloatArray(edcDb.size / step) { edcDb[it * step].toFloat() }
        val envCount = (tailEnd - onset) / step
        val envOut = FloatArray(envCount) { k ->
            var a = 0.0
            val s = onset + k * step
            for (i in s until s + step) a += p[i]
            (10.0 * log10(max(a / step, 1e-30) / peakPow)).toFloat()
        }
        return DecayCurve(step.toDouble() / fs, edcOut, envOut, noiseDb)
    }

    private fun emptyBand(
        fc: Int, extended: Boolean, reasons: List<QualityReason>,
        marginDb: Double? = null, peak: Double? = null, bg: Double? = null,
    ) = BandResult(fc, extended, QualityState.INVALID, reasons, null, null, null, marginDb, peak, bg, null, null, null)

    private fun earlyExit(
        signal: PcmSignal, reasons: List<QualityReason>, peakDbfs: Double, onsetSeconds: Double?, bgDbfs: Double?,
    ) = AttemptAnalysis(
        configuration = config,
        sampleRate = signal.sampleRate,
        durationSeconds = signal.durationSeconds,
        state = QualityState.INVALID,
        reasons = reasons,
        onsetSeconds = onsetSeconds,
        peakDbfs = peakDbfs,
        backgroundRmsDbfs = bgDbfs,
        bands = config.bandsHz.map { emptyBand(it, it !in config.primaryBandsHz, reasons) },
    )

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
}
