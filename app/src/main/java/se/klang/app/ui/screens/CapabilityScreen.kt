package se.klang.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.ui.SectionCard
import se.klang.app.ui.StateChip
import se.klang.app.ui.fmt
import se.klang.app.ui.rememberWithMicPermission

/**
 * Feasibility screen: what the device claims, plus a test capture that runs the full analysis
 * without saving to a session. The test WAV can be shared for analysis in a reference tool.
 */
@Composable
fun CapabilityScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refreshCapabilities() }
    val caps = vm.capabilities
    val startTest = rememberWithMicPermission(onDenied = { vm.permissionDenied() }) { vm.runTestCapture() }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            SectionCard("Device") {
                if (caps == null) Text("Reading capabilities…") else {
                    Text("${caps.manufacturer} ${caps.model}, Android ${caps.androidRelease} (API ${caps.sdkInt})")
                    Text(
                        "Unprocessed audio source: " + when {
                            caps.unprocessedSupported -> "reported as supported"
                            caps.unprocessedProperty == null -> "not reported — will use VOICE_RECOGNITION (fallback, flagged)"
                            else -> "not supported (${caps.unprocessedProperty}) — will use VOICE_RECOGNITION (fallback, flagged)"
                        }
                    )
                    Text("Formats (mono): " + caps.formats.entries.joinToString { (r, e) -> "$r Hz: ${e.ifEmpty { listOf("none") }.joinToString("/")}" })
                    Text("Native output rate: ${caps.nativeOutputSampleRate ?: "unknown"} Hz")
                    Text("Effects offered: AGC ${yn(caps.agcAvailable)}, noise suppression ${yn(caps.noiseSuppressorAvailable)}, echo cancellation ${yn(caps.echoCancelerAvailable)}")
                    Text("Inputs: " + caps.inputs.joinToString { "${it.type} ${it.productName}" })
                    Text(
                        "These are the device's own claims. Disabling effects through the API does not prove that all vendor processing is off; " +
                            "check test recordings (e.g. in Room EQ Wizard) on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionCard("Test capture") {
                Text("Records 5 s: stay quiet, clap once when prompted, then stay still. Nothing is saved to a session.")
                Button(enabled = !vm.isCapturing, onClick = startTest) { Text("Start test capture") }
                CapturePanel(vm)
            }
        }
        val t = vm.testCapture
        if (t != null) {
            item {
                SectionCard("Capture configuration") {
                    val c = t.configuration
                    Text("Source ${c.audioSource}${if (c.sourceFallback) " (fallback)" else ""}, ${c.actualSampleRate} Hz ${c.encoding}, ${c.channelCount} ch")
                    Text("Input: ${c.inputDevice?.let { "${it.type} ${it.productName}" } ?: "unknown"}")
                    Text("Effects: " + c.effects.joinToString { "${it.name}: ${if (!it.available) "not offered" else "enabled=${it.enabled}"}" })
                    c.recordingConfigNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (t.events.isEmpty()) Text("No capture events (no route change, overrun or silencing detected).")
                    t.events.forEach { Text("⚠ ${AppViewModel.describeEvent(it)}") }
                }
            }
            item {
                SectionCard("Analysis") {
                    val a = t.analysis
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Attempt"); StateChip(a.state) }
                    Text("Peak ${fmt(a.peakDbfs, 1)} dBFS · background ${fmt(a.backgroundRmsDbfs, 1)} dBFS RMS (relative levels, not SPL)")
                    a.reasons.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    a.bands.forEach { b ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${b.centerHz} Hz${if (b.extended) " (ext.)" else ""}")
                            StateChip(b.state)
                            Text("T20 ${fmt(b.t20?.seconds)} s · margin ${fmt(b.marginDb, 0)} dB")
                        }
                    }
                    OutlinedButton(onClick = {
                        scope.launch { vm.shareTestWav()?.let { context.startActivity(Intent.createChooser(it, "Share test WAV")) } }
                    }) { Text("Share WAV") }
                }
            }
        }
    }
}

private fun yn(b: Boolean) = if (b) "yes" else "no"
