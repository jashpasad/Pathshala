package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Arm device profile, model management, on-device benchmark and verifiable privacy statement. */
@Composable
fun EngineScreen(vm: AppViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); c.moveToFirst(); c.getString(i)
            } ?: "model.pte"
            vm.importModelFile(uri, name)
        }
    }
    val d = vm.device
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Device (Arm CPU)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("SoC: ${d.soc} · ${d.cores} cores · RAM ${"%.1f".format(d.totalRamGb)} GB")
                Text("ISA: ${d.isaSummary()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("KleidiAI int8 kernels via XNNPACK use dotprod / i8mm when present.", style = MaterialTheme.typography.bodySmall)
                Text("Thermal: ${d.thermalLabel()} · Recommended: ${d.recommendedModel}")
            }
        }
        SectionTitle("Model")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(vm.engineStatus)
                Text("Engine: ${vm.engine.name}", style = MaterialTheme.typography.bodySmall)
                Text("adb push your .pte and tokenizer.model to:\n${vm.modelDirs.first().absolutePath}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 6.dp)) {
                    Button(onClick = { scope.launch { vm.tryAutoLoadModel() } }, enabled = !vm.busy) { Text("Scan & load") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("Import file…") }
                }
            }
        }
        SectionTitle("On-device benchmark")
        Button(onClick = { vm.runBenchmark() }, enabled = !vm.busy) { Text("Run 3 PathShala workloads") }
        Text("Unplug the charger and close other apps. Results are copied to the log (adb logcat -s PathShala/Bench) for the report.", style = MaterialTheme.typography.bodySmall)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        vm.bench.forEach { r ->
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(r.label, fontWeight = FontWeight.SemiBold)
                    r.stats?.let { s ->
                        Text("prompt ${s.promptTokens} tok · gen ${s.generatedTokens} tok · TTFT ${s.ttftMs} ms", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("prefill ${"%.1f".format(s.prefillTokPerSec)} tok/s · decode ${"%.1f".format(s.decodeTokPerSec)} tok/s · RSS ${s.rssMb} MB", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("CPU ${"%.2f".format(s.coresBusy)} cores busy of ${d.cores} · ${s.peakThreads} threads" +
                            (s.avgPowerW?.let { " · ${"%.2f".format(it)} W · ${"%.1f".format(s.joulesPer100Tok ?: 0.0)} J/100 tok" } ?: " · power n/a") +
                            if (s.charging) " · ⚠ charging: unplug for valid power" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.output.isNotBlank()) Text(r.output, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Symbolic engine on this phone: last diagnostic step " + (vm.lastDiagStepMs?.let { "%.1f ms".format(it) } ?: "–") +
            " · last replan " + (vm.lastPlanMs?.let { "%.2f ms".format(it) } ?: "–"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        SectionTitle("Privacy you can verify")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val perms = vm.requestedPermissions()
                Text("Permissions this app requests: " + if (perms.isEmpty()) "none" else perms.joinToString(), fontWeight = FontWeight.SemiBold)
                Text("PathShala has no INTERNET permission, so Android itself prevents it from sending your goals, answers or chats anywhere. The model, your learner profile and plan live only in this app's private storage.", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("Demo controls")
        Text("Simulated day offset: +${vm.dayOffset}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.advanceDay() }) { Text("Advance one day (shows forgetting + reviews)") }
    }
}
