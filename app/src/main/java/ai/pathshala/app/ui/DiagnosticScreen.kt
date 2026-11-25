package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticScreen(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack ?: return
    val d = vm.diagnostic
    if (d == null) { SelfRating(vm, modifier); return }
    val item = vm.diagItem
    // recompute on every answer (diagVersion changes)
    val unc = remember(vm.diagVersion) { d.uncertainty() / d.maxUncertainty() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Quick diagnostic", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Each question is chosen to tell me the most about the whole skill map, so we stop as soon as I'm confident.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Question ${d.questionsAsked + 1} · uncertainty remaining ${(unc * 100).toInt()}%")
        LinearProgressIndicator(progress = { (1 - unc).toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        if (item == null) { Button(onClick = { vm.finishDiagnostic() }) { Text("See my skill map") }; return@Column }
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(pack.skill(item.skillId).name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(item.stem, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                item.options.forEachIndexed { i, o ->
                    OutlinedButton(onClick = { vm.answerDiagnostic(i) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(o) }
                }
                TextButton(onClick = { vm.answerDiagnostic(-1) }) { Text("I don't know") }
            }
        }
        TextButton(onClick = { vm.finishDiagnostic() }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop here and build my plan") }
    }
}

/** For AI-drafted packs without a calibrated item bank: confidence self-rating per topic. */
@Composable
private fun SelfRating(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack ?: return
    val ratings = remember { mutableStateMapOf<String, Float>().apply { pack.skills.forEach { put(it.id, 0.2f) } } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("How confident are you in each topic?", style = MaterialTheme.typography.titleLarge)
        Text("This roadmap has no verified question bank yet, so we start from your self-rating and refine it through teach-back checks.", style = MaterialTheme.typography.bodySmall)
        pack.skills.forEach { s ->
            Text("${s.name}: ${((ratings[s.id] ?: 0f) * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
            Slider(ratings[s.id] ?: 0f, { ratings[s.id] = it })
        }
        Button(onClick = { vm.finishDiagnostic(ratings.mapValues { it.value.toDouble() }) }) { Text("Build my plan") }
    }
}
