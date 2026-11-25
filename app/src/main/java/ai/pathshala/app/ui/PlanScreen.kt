package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import ai.pathshala.core.BlockKind
import ai.pathshala.core.Reasons
import ai.pathshala.core.StepStatus
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack; val plan = vm.plan; val l = vm.learner
    if (pack == null || plan == null || l == null) { EmptyState("Set a goal and take the diagnostic to get your plan.", modifier); return }
    val lang = l.goal.language
    val todayBlocks = plan.blocks.filter { it.day == vm.today }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(pack.title, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Readiness ${(plan.currentScore * 100).toInt()}% → ${(plan.projectedScore * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text("Goal coverage by deadline: ${(plan.coverage * 100).toInt()}% · needs ${"%.0f".format(plan.neededHours)} h" +
                        (plan.budgetHours?.let { " of ${"%.0f".format(it)} h available" } ?: ""))
                    plan.finishDay?.let { Text("Path finishes ~${LocalDate.ofEpochDay(it.toLong())}") }
                    if (plan.triage) Text("⚠ Not enough time for everything. I prioritised by value per hour and deferred: " +
                        plan.path.filter { it.status == StepStatus.DEFERRED }.joinToString { pack.skill(it.skillId).name }, color = Color(0xFFB0571A))
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(onClick = { vm.narratePlan() }, enabled = !vm.busy) { Text("Explain my plan") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.advanceDay() }) { Text("Next day ▶") }
                    }
                    if (vm.narration.isNotBlank()) Text(vm.narration, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionTitle("Today · ${LocalDate.ofEpochDay(vm.today.toLong())}") }
        if (todayBlocks.isEmpty()) item { Text("Rest day — nothing scheduled. 🌿") }
        items(todayBlocks) { b ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    val icon = when (b.kind) { BlockKind.LEARN -> "📖"; BlockKind.PRACTICE -> "✍️"; BlockKind.REVIEW -> "🔁"; BlockKind.REMEDIATE -> "🧩" }
                    Text("$icon ${b.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${pack.skill(b.skillId).name} · ${b.minutes} min", fontWeight = FontWeight.SemiBold)
                    FlowRow(Modifier.padding(top = 4.dp)) { b.reasons.take(3).forEach { Pill(Reasons.render(it, pack, lang)) } }
                    Row {
                        if (b.kind == BlockKind.LEARN) {
                            pack.skill(b.skillId).resources.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                            TextButton(onClick = { vm.markStudied(b) }) { Text("Done") }
                        } else TextButton(onClick = { vm.openPractice(b.skillId) }) { Text("Start") }
                    }
                }
            }
        }
        item { SectionTitle("Next 7 days") }
        item {
            Row(Modifier.fillMaxWidth()) {
                (1..7).forEach { off ->
                    val d = vm.today + off
                    val mins = plan.blocks.filter { it.day == d }.sumOf { it.minutes }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(LocalDate.ofEpochDay(d.toLong()).dayOfWeek.name.take(2))
                        Text(if (mins == 0) "–" else "${mins}m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { SectionTitle("Learning path") }
        items(plan.path) { s ->
            val name = pack.skill(s.skillId).name
            val tag = when (s.status) { StepStatus.SCHEDULED -> "→"; StepStatus.DEFERRED -> "⏸"; StepStatus.MASTERED -> "✓" }
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("$tag $name" + if (s.status == StepStatus.SCHEDULED) " · ~${"%.1f".format(s.remainingHours)} h" else "")
                Text(s.reasons.take(2).joinToString(" · ") { Reasons.render(it, pack, lang) }, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
