package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import ai.pathshala.app.Tab
import ai.pathshala.core.LearnerModel
import ai.pathshala.core.SkillGraph
import ai.pathshala.core.StepStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Layered DAG of the skill pack, coloured by the learner's current effective mastery. */
@Composable
fun SkillMapScreen(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack; val l = vm.learner
    if (pack == null || l == null) { EmptyState("Set a goal first.", modifier); return }
    val graph = remember(pack.id) { SkillGraph(pack) }
    val layers = remember(pack.id) { graph.layers() }
    val byLayer = remember(pack.id) { graph.order.groupBy { layers.getValue(it.id) } }
    val deferred = vm.plan?.path?.filter { it.status == StepStatus.DEFERRED }?.map { it.skillId }?.toSet().orEmpty()
    var selected by remember { mutableStateOf<String?>(null) }
    val positions = remember(pack.id) { HashMap<String, Offset>() }   // layout cache, not UI state

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(pack.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Tap a topic. Arrows point from prerequisite to dependent. Dashed = deferred by the planner.", style = MaterialTheme.typography.bodySmall)
        val textColor = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.fillMaxWidth().weight(1f).pointerInput(pack.id) {
            detectTapGestures { tap -> selected = positions.minByOrNull { (it.value - tap).getDistance() }?.takeIf { (it.value - tap).getDistance() < 80f }?.key }
        }) {
            val nLayers = (byLayer.keys.maxOrNull() ?: 0) + 1
            byLayer.forEach { (layer, skills) ->
                skills.forEachIndexed { i, s ->
                    val x = size.width * (i + 1f) / (skills.size + 1f)
                    val y = size.height * (layer + 0.5f) / nLayers
                    positions[s.id] = Offset(x, y)
                }
            }
            pack.skills.forEach { s -> s.prereqs.forEach { p ->
                val a = positions[p]; val b = positions[s.id]
                if (a != null && b != null) drawLine(Color.Gray.copy(alpha = 0.5f), a, b, strokeWidth = 3f)
            } }
            pack.skills.forEach { s ->
                val c = positions[s.id] ?: return@forEach
                val m = LearnerModel.effectiveMastery(l, s.id, vm.today)
                drawCircle(masteryColor(m), radius = if (s.id == selected) 34f else 26f, center = c)
                if (s.id in deferred) drawCircle(Color.DarkGray, 32f, c, style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                // stagger labels within a layer so neighbours never collide
                val idx = byLayer.getValue(layers.getValue(s.id)).indexOf(s)
                val label = if (s.name.length > 14) s.name.take(13) + "…" else s.name
                drawContext.canvas.nativeCanvas.drawText(
                    label, c.x, c.y + if (idx % 2 == 0) 52f else 84f,
                    android.graphics.Paint().apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 24f; color = android.graphics.Color.rgb((textColor.red * 255).toInt(), (textColor.green * 255).toInt(), (textColor.blue * 255).toInt()) },
                )
            }
        }
        Row(Modifier.padding(vertical = 4.dp)) {
            listOf(0.1 to "gap", 0.3 to "", 0.5 to "", 0.7 to "", 0.9 to "mastered").forEach { (p, t) -> Pill(t.ifEmpty { "  " }, masteryColor(p)) }
        }
        selected?.let { id ->
            val s = pack.skill(id); val st = l.skills.getValue(id)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name + if (s.nameHi.isNotBlank()) " · ${s.nameHi}" else "", fontWeight = FontWeight.SemiBold)
                    Text("Mastery ${(LearnerModel.effectiveMastery(l, id, vm.today) * 100).toInt()}% · ${st.correct}/${st.attempts} correct · ~${s.hours} h to learn")
                    if (s.prereqs.isNotEmpty()) Text("Needs: " + s.prereqs.joinToString { pack.skill(it).name }, style = MaterialTheme.typography.bodySmall)
                    s.resources.firstOrNull()?.let { Text("Resource: $it", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        if (s.items.isNotEmpty() || s.keyPoints.isNotEmpty()) Button(onClick = { vm.openPractice(id) }) { Text("Practice") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.selectTutorSkill(id); vm.tab = Tab.TUTOR }) { Text("Ask tutor") }
                    }
                }
            }
        }
    }
}
