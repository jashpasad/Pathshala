package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Saffron = Color(0xFFE8772E)
private val Indigo = Color(0xFF2F3E9E)
private val Leaf = Color(0xFF2E8B57)

@Composable
fun PathShalaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Indigo, secondary = Saffron, tertiary = Leaf),
        content = content,
    )
}

/** Mastery colour scale: red (gap) -> amber -> green (mastered). */
fun masteryColor(p: Double): Color = when {
    p >= 0.8 -> Color(0xFF2E8B57)
    p >= 0.6 -> Color(0xFF9BBF3B)
    p >= 0.4 -> Color(0xFFE8B02E)
    p >= 0.2 -> Color(0xFFE8772E)
    else -> Color(0xFFD64545)
}

@Composable
fun EngineBadge(vm: AppViewModel) {
    val llama = vm.engine.isLlama
    Row(
        Modifier.padding(end = 12.dp).background(if (llama) Color(0xFFE6F4EA) else Color(0xFFFFF1E0), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (llama) "● Llama on-device" else "● Template mode", fontSize = 12.sp, color = if (llama) Color(0xFF1E7B3C) else Color(0xFFB0571A))
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(text, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp).background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun EmptyState(msg: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(msg, style = MaterialTheme.typography.bodyLarge) }
}
