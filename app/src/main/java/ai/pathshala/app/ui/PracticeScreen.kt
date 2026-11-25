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

/**
 * Practice with guardrails (Bastani et al., PNAS 2025): the learner must attempt the item; Llama
 * offers a 3-step hint ladder that never states the answer. Teach-back (Feynman) explanations are
 * graded by Llama against the concept card and fed back as soft evidence.
 */
@Composable
fun PracticeScreen(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack ?: return
    val sid = vm.practiceSkill ?: return
    val skill = pack.skill(sid)
    val item = vm.practiceItem
    var explanation by remember(sid) { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row {
            Text("Practice · ${skill.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.practiceSkill = null }) { Text("Close") }
        }
        if (item != null) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(item.stem, style = MaterialTheme.typography.titleMedium)
                    item.options.forEachIndexed { i, o ->
                        OutlinedButton(onClick = { vm.answerPractice(i) }, enabled = vm.practiceFeedback == null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(o) }
                    }
                    if (vm.practiceFeedback == null) Row {
                        TextButton(onClick = { vm.requestHint() }, enabled = !vm.busy && vm.hintLevel < 3) {
                            Text(if (vm.hintLevel == 0) "Need a hint?" else "Another hint (${vm.hintLevel}/3)")
                        }
                    }
                    if (vm.hintText.isNotBlank()) Text("💡 ${vm.hintText}", Modifier.padding(top = 6.dp))
                    vm.practiceFeedback?.let {
                        Text(it, Modifier.padding(top = 8.dp))
                        Button(onClick = { vm.nextPracticeItem() }, modifier = Modifier.padding(top = 6.dp)) { Text("Next question") }
                    }
                }
            }
        }
        if (skill.keyPoints.isNotEmpty()) {
            SectionTitle("Teach it back (Feynman check)")
            Text("Explain ${skill.name} in your own words, as if to a friend. I'll check which key ideas you covered.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(explanation, { explanation = it }, Modifier.fillMaxWidth(), minLines = 3)
            Button(onClick = { vm.submitTeachBack(explanation) }, enabled = !vm.busy && explanation.length > 20, modifier = Modifier.padding(top = 6.dp)) { Text("Check my explanation") }
            vm.teachBackResult?.let { Text(it, Modifier.padding(top = 6.dp)) }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
