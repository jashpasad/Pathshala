package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import ai.pathshala.core.SelfLevel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val examples = listOf(
    "I want to crack JEE Mains physics in 3 months, 2 hours daily. Weak in rotation. I love cricket.",
    "Become a data analyst: learn Python, pandas and SQL in 8 weeks, 1 hour a day",
    "mujhe GATE chemical ki taiyari karni hai, 4 mahine me, roz 3 ghante",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalScreen(vm: AppViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("What do you want to learn?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Type freely in English, Hindi or Hinglish. Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            FilterChip(selected = vm.language == "en", onClick = { vm.language = "en" }, label = { Text("English") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = vm.language == "hi", onClick = { vm.language = "hi" }, label = { Text("हिंदी") })
        }
        OutlinedTextField(vm.goalText, { vm.goalText = it }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3,
            label = { Text("My goal") })
        FlowRow(Modifier.padding(top = 6.dp)) {
            examples.forEach { ex -> AssistChip(onClick = { vm.goalText = ex }, label = { Text(ex.take(34) + "…") }, modifier = Modifier.padding(end = 6.dp)) }
        }
        Button(onClick = { vm.submitGoal() }, enabled = !vm.busy && vm.goalText.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) {
            Text(if (vm.busy) "Understanding…" else "Understand my goal")
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        vm.clarify?.let { q ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("PathShala asks: $q", Modifier.padding(12.dp))
            }
        }

        vm.draftGoal?.let { g ->
            val pack = vm.allPacks().firstOrNull { it.id == g.packId }
            SectionTitle("Here's what I understood")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    fun src(k: String) = g.provenance[k]?.let { " · via $it" } ?: " · default"
                    Text("Learning path", fontWeight = FontWeight.SemiBold)
                    FlowRow {
                        vm.allPacks().forEach { p ->
                            FilterChip(selected = p.id == g.packId, onClick = { vm.updateDraft { it.copy(packId = p.id) } },
                                label = { Text(p.title + if (!p.verified) " (AI draft)" else "") }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Text("source${src("pack")}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Hours per week: ${"%.1f".format(g.hoursPerWeek)}${src("hours")}")
                    Slider(g.hoursPerWeek.toFloat(), { v -> vm.updateDraft { it.copy(hoursPerWeek = v.toDouble(), provenance = it.provenance + ("hours" to "user")) } }, valueRange = 1f..30f)
                    Text("Deadline: ${g.deadlineDays?.let { "$it days" } ?: "none"}${src("deadline")}")
                    Slider((g.deadlineDays ?: 0).toFloat(), { v -> vm.updateDraft { it.copy(deadlineDays = v.toInt().takeIf { d -> d > 0 }, provenance = it.provenance + ("deadline" to "user")) } }, valueRange = 0f..365f)
                    Text("Starting level${src("level")}")
                    Row { SelfLevel.entries.forEach { lv -> FilterChip(selected = g.level == lv, onClick = { vm.updateDraft { it.copy(level = lv) } }, label = { Text(lv.name.lowercase()) }, modifier = Modifier.padding(end = 6.dp)) } }
                    if (g.interests.isNotEmpty()) Text("Interests (used for examples): ${g.interests.joinToString()}${src("interests")}")
                    if (g.focusSkillIds.isNotEmpty() && pack != null) Text("You flagged: ${g.focusSkillIds.mapNotNull { pack.skillOrNull(it)?.name }.joinToString()}")
                    if (pack != null && !pack.verified) Text("⚠ This roadmap was drafted by the on-device model and is not expert-verified.", color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = { vm.confirmGoal() }, modifier = Modifier.padding(top = 10.dp)) { Text("Start quick diagnostic (≤12 questions)") }
        }

        if (vm.learner != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { vm.resetAll() }) { Text("Reset all learner data on this device") }
        }
    }
}
