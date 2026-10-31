package se.klang.cli

import kotlinx.serialization.json.Json
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.DecayAnalyzer
import se.klang.dsp.FilterDirection
import se.klang.dsp.SyntheticImpulse
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import se.klang.sessions.AnalysisRecord
import se.klang.sessions.Records
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Desktop analysis of WAV files with the same DSP as the app, for validation against reference
 * tools such as Room EQ Wizard.
 *
 *   klang-analyse [--json] [--reversed] [--channel N] file.wav...
 *   klang-analyse --synth out.wav [--t60 0.5] [--seed 1] [--noise-dbfs -90]
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || "--help" in args) {
        println(
            """
            Usage:
              klang-analyse [--json] [--reversed] [--channel N] FILE.wav...
              klang-analyse --synth OUT.wav [--t60 SECONDS] [--seed N] [--noise-dbfs DBFS]
            """.trimIndent()
        )
        exitProcess(if (args.isEmpty()) 1 else 0)
    }
    val opts = args.toMutableList()
    fun flag(name: String) = opts.remove(name)
    fun value(name: String): String? {
        val i = opts.indexOf(name)
        if (i < 0 || i + 1 >= opts.size) return null
        val v = opts[i + 1]; opts.removeAt(i + 1); opts.removeAt(i); return v
    }

    value("--synth")?.let { out ->
        val t60 = value("--t60")?.toDouble() ?: 0.5
        val spec = SyntheticImpulse.Spec(
            decaySeconds = listOf(250, 500, 1000, 2000, 4000).associateWith { t60 },
            seed = value("--seed")?.toLong() ?: 1L,
            noiseRmsDbfs = value("--noise-dbfs")?.toDouble() ?: -90.0,
            durationSeconds = 0.75 + 2.5 * t60 + 0.75,
        )
        val sig = SyntheticImpulse.generate(spec)
        File(out).outputStream().use { Wav.write(it, sig.samples, sig.sampleRate, WavEncoding.FLOAT_32) }
        println("Wrote $out (T60 = $t60 s in every band, synthetic decaying noise)")
        return
    }

    val asJson = flag("--json")
    val config = AnalysisConfiguration(filterDirection = if (flag("--reversed")) FilterDirection.TIME_REVERSED else FilterDirection.FORWARD)
    val channel = value("--channel")?.toInt() ?: 0
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val analyzer = DecayAnalyzer(config)
    for (path in opts) {
        val decoded = File(path).inputStream().use { Wav.read(it, channel) }
        val record = Records.of(analyzer.analyze(decoded.signal), System.currentTimeMillis())
        if (asJson) println(json.encodeToString(AnalysisRecord.serializer(), record)) else printTable(path, decoded.info.toString(), record)
    }
}

private fun printTable(path: String, info: String, r: AnalysisRecord) {
    fun f(v: Double?, d: Int = 3) = v?.let { String.format(Locale.ROOT, "%.${d}f", it) } ?: "—"
    println("$path  ($info)")
    println("  ${r.algorithmId} ${r.algorithmVersion}, filter direction ${r.configuration["filterDirection"]}")
    println("  attempt: ${r.state}  onset ${f(r.onsetSeconds)} s  peak ${f(r.peakDbfs, 1)} dBFS  background RMS ${f(r.backgroundRmsDbfs, 1)} dBFS")
    r.reasons.forEach { println("    ${it.severity} ${it.code}: ${it.message}") }
    println("  band     state         T20 [s]  EDT* [s]  margin [dB]  fit (s)        ξ [‰]")
    for (b in r.bands) {
        println(
            String.format(
                Locale.ROOT, "  %5d%s  %-12s  %7s  %8s  %11s  %-13s  %5s",
                b.centerHz, if (b.extended) "e" else " ", b.state, f(b.t20?.seconds), f(b.edt?.seconds),
                f(b.marginDb, 1), b.t20?.let { "${f(it.startSeconds)}–${f(it.endSeconds)}" } ?: "—", f(b.t20?.nonLinearityPermille, 1),
            )
        )
        b.reasons.filter { r.reasons.none { a -> a.code == it.code } }.forEach { println("           ${it.severity} ${it.code}: ${it.message}") }
    }
    println("  e = extended band. *EDT is secondary and not yet validated (0…−10 dB fit, extrapolated to 60 dB).")
    println("  Levels are dBFS (relative to digital full scale), not dB SPL.")
}
