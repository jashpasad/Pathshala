package ai.pathshala.app

import ai.pathshala.app.ui.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PathShalaTheme { Root(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PathShala") },
                actions = {
                    EngineBadge(vm)
                },
            )
        },
        bottomBar = {
            if (!vm.inDiagnostic && vm.practiceSkill == null) NavigationBar {
                tabItem(vm, Tab.GOAL, "Goal", Icons.Filled.Flag)
                tabItem(vm, Tab.MAP, "Skill map", Icons.Filled.Hub)
                tabItem(vm, Tab.PLAN, "Plan", Icons.Filled.CalendarMonth)
                tabItem(vm, Tab.TUTOR, "Tutor", Icons.Filled.Forum)
                tabItem(vm, Tab.ENGINE, "Engine", Icons.Filled.Memory)
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when {
            vm.inDiagnostic -> DiagnosticScreen(vm, m)
            vm.practiceSkill != null -> PracticeScreen(vm, m)
            else -> when (vm.tab) {
                Tab.GOAL -> GoalScreen(vm, m)
                Tab.MAP -> SkillMapScreen(vm, m)
                Tab.PLAN -> PlanScreen(vm, m)
                Tab.TUTOR -> TutorScreen(vm, m)
                Tab.ENGINE -> EngineScreen(vm, m)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.tabItem(vm: AppViewModel, t: Tab, label: String, icon: ImageVector) {
    NavigationBarItem(selected = vm.tab == t, onClick = { vm.tab = t }, icon = { Icon(icon, label) }, label = { Text(label) })
}
