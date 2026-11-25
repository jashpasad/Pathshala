package ai.pathshala.app.ui

import ai.pathshala.app.AppViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TutorScreen(vm: AppViewModel, modifier: Modifier) {
    val pack = vm.pack
    if (pack == null) { EmptyState("Set a goal first.", modifier); return }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.chat.lastOrNull()?.text?.length) { if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size - 1) }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Socratic tutor — grounded in the topic's concept card. It guides; it won't just hand you answers.", style = MaterialTheme.typography.bodySmall)
        LazyRow(Modifier.padding(vertical = 6.dp)) {
            items(pack.skills) { s ->
                FilterChip(selected = vm.tutorSkill == s.id, onClick = { vm.selectTutorSkill(s.id) }, label = { Text(s.name.take(22)) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(vm.chat) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text.ifBlank { "…" }, Modifier.padding(4.dp).widthIn(max = 300.dp)
                        .background(if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF1F3F4), RoundedCornerShape(12.dp)).padding(10.dp))
                }
            }
        }
        vm.lastStats?.takeIf { vm.engine.isLlama }?.let {
            Text("last reply: ${it.generatedTokens} tok · TTFT ${it.ttftMs} ms · decode ${"%.1f".format(it.decodeTokPerSec)} tok/s", style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about this topic…") })
            if (vm.busy) TextButton(onClick = { vm.stopGeneration() }) { Text("Stop") }
            else Button(onClick = { vm.sendTutor(input); input = "" }, enabled = input.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) { Text("Send") }
        }
    }
}
