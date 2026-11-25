package ai.pathshala.app

import ai.pathshala.app.ui.PathShalaTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real app UI (template mode, no model) through a scripted learner journey. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class ScreenshotTour {
    @get:Rule val rule = createComposeRule()

    /** Let coroutines, Handler posts and template streaming finish (Robolectric paused main looper). */
    private fun settle(ms: Long = 4000) {
        var t = 0L
        while (t < ms) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5); t += 100
        }
        rule.waitForIdle()
    }
    private fun shot(name: String) { settle(); rule.onRoot().captureRoboImage("build/tour/$name.png") }

    @Test fun tour() {
        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        vm.resetAll()
        rule.setContent { PathShalaTheme { Root(vm) } }
        vm.goalText = "mujhe GATE chemical ki taiyari karni hai, 4 mahine me, roz 3 ghante. weak in mass transfer"
        shot("01_goal_typed")
        vm.submitGoal(); shot("02_goal_parsed")
        vm.confirmGoal(); shot("03_diagnostic_q1")
        // a learner who knows the foundations but not the advanced units
        val strong = setOf("eng_math", "mass_energy_balance", "thermo", "fluid_mech", "chem_tech", "plant_design")
        var i = 0
        while (vm.inDiagnostic && i < 12) {
            val item = vm.diagItem ?: break
            vm.answerDiagnostic(if (item.skillId in strong) item.answer else (item.answer + 1) % item.options.size)
            settle(800)
            if (i == 4) shot("04_diagnostic_q6")
            i++
        }
        if (vm.inDiagnostic) vm.finishDiagnostic()
        shot("05_skill_map")
        vm.tab = Tab.PLAN; shot("06_plan")
        vm.narratePlan(); shot("07_plan_narrated")
        val first = vm.plan!!.path.first { it.status == ai.pathshala.core.StepStatus.SCHEDULED }.skillId
        vm.openPractice(first); shot("08_practice")
        vm.requestHint(); shot("09_practice_hint")
        vm.answerPractice((vm.practiceItem!!.answer + 1) % 4); shot("10_practice_feedback")
        vm.practiceSkill = null
        vm.advanceDay(); vm.advanceDay(); vm.advanceDay(); vm.advanceDay(); vm.tab = Tab.PLAN; shot("11_plan_day4")

    }
}
