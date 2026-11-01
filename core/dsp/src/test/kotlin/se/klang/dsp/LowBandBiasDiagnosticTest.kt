package se.klang.dsp

import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Not an assertion test and not part of the default run (opt-in, like [ValidationReportTest]):
 * diagnoses the 250 Hz bias on DECAYING_NOISE at short decays (issue #11).
 *
 * Run: ./gradlew -p core :dsp:test --tests '*LowBandBiasDiagnosticTest' -Dklang.diagnostics=true --rerun-tasks
 * Output: core/dsp/build/diagnostics/low-band-bias.txt
 *
 * For each seed it compares the analyser's T20 with "oracle" T20 values that bypass parts of the
 * analyser, so each candidate cause is isolated:
 *  - oracle EDC: plain Schroeder integral of the analyser's own band signal to 0.6 s after the true
 *    onset (the background is ~90 dB down, so no truncation or compensation is needed), same
 *    −5…−25 dB first-crossing fit. Analyser vs this oracle = effect of the truncation search and the
 *    envelope window (the envelope only feeds the margin and the truncation point, never the EDC).
 *  - fixture component: the fixture's own 250 Hz band component alone (same seed, same noise draw,
 *    no background, no other bands), Schroeder-integrated without an analysis filter. Its spread
 *    is the per-realisation scatter of the fixture itself, independent of the analyser.
 *  - fixture component + forward / time-reversed analysis filter: adds the analyser's filter only.
 * Synthetic engineering check only, not a real-room accuracy claim.
 */
class LowBandBiasDiagnosticTest {
    private val fs = 48_000
    private val bands = listOf(250, 500, 1000, 2000, 4000)

    private fun spec(t60: Double, seed: Long, only: Int? = null) = SyntheticImpulse.Spec(
        decaySeconds = if (only == null) bands.associateWith { t60 } else mapOf(only to t60),
        durationSeconds = 0.75 + 2.5 * t60 + 0.6,
        noiseRmsDbfs = if (only == null) -90.0 else -300.0,
        seed = seed,
    )

    /** Schroeder T20 (−5…−25 dB first crossings, 60 dB extrapolation) of a band signal from [onset]. */
    private fun oracleT20(y: DoubleArray, onset: Int, lengthSeconds: Double): Double? {
        val end = minOf(y.size, onset + (lengthSeconds * fs).toInt())
        val len = end - onset
        val edc = DoubleArray(len)
        var acc = 0.0
        for (i in len - 1 downTo 0) { acc += y[onset + i] * y[onset + i]; edc[i] = acc }
        val db = DoubleArray(len) { 10 * log10(edc[it] / edc[0]) }
        val t = DoubleArray(len) { it.toDouble() / fs }
        val i1 = db.indexOfFirst { it <= -5.0 }
        val i2 = db.indexOfFirst { it <= -25.0 }
        if (i1 < 0 || i2 < 0) return null
        val f = fitLine(t, db, i1, i2 + 1) ?: return null
        return -60.0 / f.slope
    }

    private class Stats(val name: String) {
        val v = ArrayList<Double>()
        fun add(x: Double?) { if (x != null) v += x }
        fun row(): String {
            if (v.isEmpty()) return String.format(Locale.ROOT, "%-46s  n=0", name)
            val m = v.average()
            val sd = sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1).coerceAtLeast(1))
            val s = v.sorted()
            return String.format(
                Locale.ROOT, "%-46s n=%3d mean %.3f  sd %.3f  se %.3f  median %.3f  min %.3f  max %.3f",
                name, v.size, m, sd, sd / sqrt(v.size.toDouble()), s[s.size / 2], s.first(), s.last(),
            )
        }
    }

    @Test
    fun diagnose() {
        if (System.getProperty("klang.diagnostics") != "true") return
        val seeds = (System.getProperty("klang.diagnosticSeeds") ?: "200").toLong()
        val out = StringBuilder()
        out.appendLine("Low-band bias diagnostic (issue #11), DECAYING_NOISE, seeds 1..$seeds, values = T20 / true T60")
        val configs = linkedMapOf(
            "analyser forward, 32 ms window (default)" to AnalysisConfiguration(),
            "analyser TIME_REVERSED, 32 ms window" to AnalysisConfiguration(filterDirection = FilterDirection.TIME_REVERSED),
            "analyser forward, 10 ms window" to AnalysisConfiguration(envelopeCycles = 2.5),
            "analyser forward, 16 ms window" to AnalysisConfiguration(envelopeCycles = 4.0),
            "analyser forward, 64 ms window" to AnalysisConfiguration(envelopeCycles = 16.0),
        )
        for (fc in listOf(250, 500)) for (t60 in listOf(0.3, 0.5)) {
            out.appendLine()
            out.appendLine("== $fc Hz, T60 = $t60 s ==")
            val stats = configs.keys.associateWith { Stats(it) }
            val first10 = configs.keys.associateWith { Stats("  same, seeds 1..10 only") }
            val reasons = configs.keys.associateWith { sortedMapOf<String, Int>() }
            val tcStats = Stats("default: truncation point tc [s]")
            val diffTrunc = Stats("default minus oracle EDC (same band signal)")
            val oracleFwd = Stats("oracle EDC, analyser band signal, forward")
            val oracleRev = Stats("oracle EDC, analyser band signal, reversed")
            val comp = Stats("fixture component alone, no analysis filter")
            val compFwd = Stats("fixture component + forward analysis filter")
            val compRev = Stats("fixture component + reversed analysis filter")
            val bxt = Stats("default: bandwidth x T20")
            val rawFwd = Stats("raw fixture noise, fixture filter forward")
            val rawRev = Stats("raw fixture noise, fixture filter reversed")
            for (seed in 1L..seeds) {
                val sig = SyntheticImpulse.generate(spec(t60, seed))
                val trueOnset = (0.75 * fs).toInt()
                for ((name, cfg) in configs) {
                    val b = DecayAnalyzer(cfg).analyze(sig).bands.first { it.centerHz == fc }
                    val r = b.t20?.seconds?.div(t60)
                    stats[name]!!.add(r)
                    if (seed <= 10) first10[name]!!.add(r)
                    b.reasons.filter { it.severity != QualityState.VALID }.map { it.code.name }.distinct()
                        .forEach { reasons[name]!!.merge(it, 1, Int::plus) }
                    if (cfg == configs.values.first()) {
                        tcStats.add(b.truncationSeconds)
                        b.t20?.let { bxt.add(OctaveFilter(fc.toDouble(), fs).bandwidthHz * it.seconds) }
                    }
                }
                val filt = OctaveFilter(fc.toDouble(), fs)
                val yF = filt.process(sig.samples, FilterDirection.FORWARD)
                val yR = filt.process(sig.samples, FilterDirection.TIME_REVERSED)
                val oF = oracleT20(yF, trueOnset, 0.6)?.div(t60)
                oracleFwd.add(oF)
                oracleRev.add(oracleT20(yR, trueOnset, 0.6)?.div(t60))
                val def = DecayAnalyzer().analyze(sig).bands.first { it.centerHz == fc }.t20?.seconds?.div(t60)
                if (def != null && oF != null) diffTrunc.add(def - oF)
                val c = SyntheticImpulse.generate(spec(t60, seed, only = fc)).samples
                comp.add(oracleT20(DoubleArray(c.size) { c[it].toDouble() }, trueOnset, 0.6)?.div(t60))
                compFwd.add(oracleT20(filt.process(c, FilterDirection.FORWARD), trueOnset, 0.6)?.div(t60))
                compRev.add(oracleT20(filt.process(c, FilterDirection.TIME_REVERSED), trueOnset, 0.6)?.div(t60))
                // Re-draw the fixture's raw decaying noise for this band (same generator, same order:
                // the band is the first one drawn when generated alone), then band-limit it either way.
                val rng = SyntheticImpulse.Gaussian(seed)
                val raw = FloatArray(c.size)
                for (i in trueOnset until raw.size) raw[i] = (rng.next() * Math.exp(-3.0 * Math.log(10.0) * (i - trueOnset).toDouble() / fs / t60)).toFloat()
                val rf = filt.process(raw, FilterDirection.FORWARD)
                rawFwd.add(oracleT20(rf, trueOnset, 0.6)?.div(t60))
                rawRev.add(oracleT20(filt.process(raw, FilterDirection.TIME_REVERSED), trueOnset, 0.6)?.div(t60))
            }
            for (k in configs.keys) {
                out.appendLine(stats[k]!!.row())
                out.appendLine(first10[k]!!.row())
                out.appendLine("  non-valid reasons (bands): " + reasons[k]!!.entries.joinToString { "${it.key} ${it.value}" })
            }
            listOf(tcStats, bxt, diffTrunc, oracleFwd, oracleRev, comp, compFwd, compRev, rawFwd, rawRev).forEach { out.appendLine(it.row()) }
        }
        val f = File(System.getProperty("klang.diagnosticsOut", "build/diagnostics/low-band-bias.txt"))
        f.parentFile.mkdirs()
        f.writeText(out.toString())
        println(out)
    }
}
