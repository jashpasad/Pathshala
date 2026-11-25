package ai.pathshala.app

import ai.pathshala.app.llm.*
import ai.pathshala.core.*
import ai.pathshala.core.llm.*
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

enum class Tab { GOAL, MAP, PLAN, TUTOR, ENGINE }
data class ChatMsg(val fromUser: Boolean, val text: String, val engine: String = "")
data class BenchRow(val label: String, val stats: GenStats?, val output: String)

/**
 * Orchestrates the neuro-symbolic loop:
 *   language in (Llama) -> structured goal (validated) -> Graph-CAT diagnosis (symbolic)
 *   -> deadline-aware plan (symbolic) -> grounded narration / tutoring (Llama) -> evidence -> replan.
 * Everything runs and persists on-device; the app requests no network permission.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val stateFile = File(app.filesDir, "learner_state.json")
    private val genPackFile = File(app.filesDir, "generated_pack.json")
    val device = DeviceProfile(app)
    val modelDirs: List<File> = listOfNotNull(app.getExternalFilesDir("models"), File(app.filesDir, "models"), File("/data/local/tmp/llama"))

    // ---------------- engine
    private val template = TemplateEngine()
    var engine: LlmEngine by mutableStateOf(template); private set
    var engineStatus by mutableStateOf("No model loaded — template mode"); private set
    var busy by mutableStateOf(false); private set
    var lastStats by mutableStateOf<GenStats?>(null); private set
    val bench = mutableStateListOf<BenchRow>()
    /** On-device timing of the symbolic engine (shown on the Engine screen, logged for the report). */
    var lastDiagStepMs by mutableStateOf<Double?>(null); private set
    var lastPlanMs by mutableStateOf<Double?>(null); private set

    // ---------------- content + learner
    val packs: List<SkillPack> = PackRepository.loadAll()
    var generatedPack by mutableStateOf<SkillPack?>(null); private set
    var tab by mutableStateOf(Tab.GOAL)
    var dayOffset by mutableStateOf(0)            // "time travel" to demonstrate adaptation over days
    val today: Int get() = LocalDate.now().toEpochDay().toInt() + dayOffset

    var goalText by mutableStateOf("")
    var language by mutableStateOf("en")
    var draftGoal by mutableStateOf<LearnerGoal?>(null)
    var clarify by mutableStateOf<String?>(null)
    var learner by mutableStateOf<LearnerState?>(null); private set
    var plan by mutableStateOf<StudyPlan?>(null); private set
    var narration by mutableStateOf(""); private set

    // diagnostic
    var diagnostic by mutableStateOf<GraphDiagnostic?>(null); private set
    var diagItem by mutableStateOf<Item?>(null); private set
    var diagVersion by mutableStateOf(0); private set
    var inDiagnostic by mutableStateOf(false); private set

    // practice
    var practiceSkill by mutableStateOf<String?>(null)
    var practiceItem by mutableStateOf<Item?>(null); private set
    var practiceFeedback by mutableStateOf<String?>(null); private set
    var hintLevel by mutableStateOf(0); private set
    var hintText by mutableStateOf(""); private set
    var teachBackResult by mutableStateOf<String?>(null); private set

    // tutor
    var tutorSkill by mutableStateOf<String?>(null)
    val chat = mutableStateListOf<ChatMsg>()
    private var tutorPrimedFor: String? = null

    val pack: SkillPack? get() = learner?.let { l -> allPacks().firstOrNull { it.id == l.packId } }
    fun allPacks() = packs + listOfNotNull(generatedPack)

    init {
        restore()
        viewModelScope.launch { tryAutoLoadModel() }
    }

    // ======================================================================= model management
    suspend fun tryAutoLoadModel() {
        val found = ExecuTorchEngine.discover(modelDirs) ?: run {
            engineStatus = "No .pte found. Push model to ${modelDirs.first().absolutePath} or import it."
            return
        }
        loadModel(found.first, found.second)
    }

    private suspend fun loadModel(pte: File, tok: File) {
        busy = true; engineStatus = "Loading ${pte.name} (${pte.length() / 1_000_000} MB)…"
        val e = ExecuTorchEngine(pte, tok, device)
        e.load().onSuccess { ms ->
            engine = e; engineStatus = "Llama ready · loaded in ${ms} ms · ${pte.name}"
        }.onFailure {
            engineStatus = "Load failed: ${it.message}. Using template mode."
        }
        busy = false
    }

    /** Copy model/tokenizer picked via the system file picker into app storage (no network, no permission). */
    fun importModelFile(uri: Uri, displayName: String) = viewModelScope.launch {
        busy = true; engineStatus = "Copying $displayName…"
        val dir = File(getApplication<Application>().filesDir, "models").apply { mkdirs() }
        val name = when {
            displayName.endsWith(".pte") -> displayName
            displayName.contains("token") || displayName.endsWith(".model") -> "tokenizer.model"
            else -> displayName
        }
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { inp ->
                File(dir, name).outputStream().use { inp.copyTo(it, 1 shl 20) }
            }
        }
        busy = false
        engineStatus = "Copied $name"
        tryAutoLoadModel()
    }

    fun requestedPermissions(): List<String> = runCatching {
        val ctx = getApplication<Application>()
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Tokens arrive on the native runner thread; hop to the main thread (order preserved) for UI state. */
    private suspend fun llm(prompt: String, budget: Int, temp: Float = 0.6f, cont: Boolean = false, onToken: (String) -> Unit = {}): GenResult {
        val r = engine.generate(prompt, budget, temp, cont) { t -> main.post { onToken(t) } }
        r.stats?.let { lastStats = it }
        return r
    }

    // ======================================================================= 1. goal understanding
    fun submitGoal() = viewModelScope.launch {
        if (goalText.isBlank()) return@launch
        busy = true; clarify = null
        val rules = RuleGoalParser.parse(goalText, packs, packs.first().id)
        val llmOut = if (engine.isLlama) llm(Prompts.goalExtraction(goalText, packs), Prompts.GOAL_BUDGET.maxNewTokens, 0f).text else null
        var g = OutputParsers.mergeGoal(goalText, llmOut, packs, rules).copy(language = if (language == "hi") "hi" else rules.language)
        val noPackMatch = "pack" !in g.provenance
        if (noPackMatch && engine.isLlama) {
            // Open goal outside curated packs: Llama drafts a roadmap (marked unverified)
            val out = llm(Prompts.generatePack(goalText), Prompts.PACKGEN_BUDGET.maxNewTokens, 0.3f).text
            OutputParsers.parseGeneratedPack(goalText, out)?.let { gp ->
                generatedPack = gp; genPackFile.writeText(json.encodeToString(SkillPack.serializer(), gp))
                g = g.copy(packId = gp.id, provenance = g.provenance + ("pack" to "llama-generated"))
            }
        }
        draftGoal = g
        val missing = RuleGoalParser.missingFields(g).filter { !(it == "what you want to learn" && "pack" in g.provenance) }
        if (missing.isNotEmpty()) {
            clarify = if (engine.isLlama) llm(Prompts.clarify(missing, g.language), Prompts.CLARIFY_BUDGET.maxNewTokens).text
            else "Could you tell me: ${missing.joinToString(", ")}? (You can also edit the fields below.)"
        }
        busy = false
    }

    fun updateDraft(f: (LearnerGoal) -> LearnerGoal) { draftGoal = draftGoal?.let(f) }

    fun confirmGoal() {
        val g = draftGoal ?: return
        val p = allPacks().first { it.id == g.packId }
        learner = LearnerModel.init(p, g, today)
        chat.clear(); tutorPrimedFor = null; narration = ""
        startDiagnostic()
    }

    // ======================================================================= 2. Graph-CAT diagnostic
    private fun startDiagnostic() {
        val l = learner ?: return; val p = pack ?: return
        if (p.allItems.isEmpty()) { inDiagnostic = true; diagnostic = null; return } // generated pack -> self-rating
        val importance = p.skills.associate { it.id to if (it.id in l.goal.focusSkillIds) 1.5 else 1.0 }
        diagnostic = GraphDiagnostic(p, l.goal.level, skillImportance = importance, seed = System.currentTimeMillis())
        diagItem = diagnostic!!.nextItem(); diagVersion++; inDiagnostic = true
    }

    fun answerDiagnostic(choice: Int) = viewModelScope.launch {
        val d = diagnostic ?: return@launch; val item = diagItem ?: return@launch
        val t0 = System.nanoTime()
        withContext(Dispatchers.Default) { d.observe(item, choice == item.answer) }
        val stop = withContext(Dispatchers.Default) { d.shouldStop(maxQuestions = 12) }
        if (stop) finishDiagnostic() else {
            diagItem = withContext(Dispatchers.Default) { d.nextItem() }; diagVersion++
            lastDiagStepMs = (System.nanoTime() - t0) / 1e6
            android.util.Log.i("PathShala/Bench", "diagnostic_step_ms=${lastDiagStepMs}")
        }
    }

    fun finishDiagnostic(selfRatings: Map<String, Double>? = null) {
        val l = learner ?: return
        learner = if (selfRatings != null) LearnerModel.applyDiagnostic(l, selfRatings, emptyList(), today)
        else diagnostic?.let { LearnerModel.applyDiagnostic(l, it.marginals(), it.history, today) } ?: l
        inDiagnostic = false; diagItem = null
        replan(); tab = Tab.MAP
    }

    // ======================================================================= 3. planning
    fun replan() {
        val l = learner ?: return; val p = pack ?: return
        val t0 = System.nanoTime()
        plan = Planner.plan(p, l, today)
        lastPlanMs = (System.nanoTime() - t0) / 1e6
        android.util.Log.i("PathShala/Bench", "plan_ms=${lastPlanMs}")
        persist()
    }

    fun narratePlan() = viewModelScope.launch {
        val p = pack ?: return@launch; val l = learner ?: return@launch; val pl = plan ?: return@launch
        busy = true; narration = ""
        val todayLines = pl.blocks.filter { it.day == today }.map { "${it.kind.name.lowercase()} ${p.skill(it.skillId).name} ${it.minutes} min" }
        llm(Prompts.narratePlan(p, l.goal, pl, todayLines), Prompts.NARRATE_BUDGET.maxNewTokens) { narration += it }
        busy = false
    }

    fun markStudied(block: PlanBlock) {
        val l = learner ?: return
        learner = LearnerModel.recordStudy(l, block.skillId, block.minutes, today)
        replan()
    }

    fun advanceDay() { dayOffset += 1; replan() }

    // ======================================================================= 4. practice + hints + teach-back
    fun openPractice(skillId: String) {
        val p = pack ?: return
        practiceSkill = skillId; practiceFeedback = null; hintLevel = 0; hintText = ""; teachBackResult = null
        val items = p.skill(skillId).items
        val done = learner?.events?.filter { it.skillId == skillId }?.map { it.detail }?.toSet().orEmpty()
        practiceItem = items.firstOrNull { it.id !in done } ?: items.randomOrNull()
    }

    fun answerPractice(choice: Int) {
        val item = practiceItem ?: return; val l = learner ?: return; val p = pack ?: return
        val ok = choice == item.answer
        learner = LearnerModel.recordAnswer(l, item, ok, today)
        val m = learner!!.skills.getValue(item.skillId).pMastery
        practiceFeedback = (if (ok) "✓ Correct. " else "✗ Not quite — answer: ${item.options[item.answer]}. ") +
            item.explanation + "\nMastery of ${p.skill(item.skillId).name}: ${(m * 100).toInt()}%"
        replan()
    }

    fun nextPracticeItem() = practiceSkill?.let { openPractice(it) }

    fun requestHint() = viewModelScope.launch {
        val item = practiceItem ?: return@launch; val p = pack ?: return@launch
        hintLevel = (hintLevel + 1).coerceAtMost(3); hintText = ""; busy = true
        val skill = p.skill(item.skillId)
        template.facts = skill.keyPoints.drop(hintLevel - 1)
        llm(Prompts.hint(skill, item, hintLevel, learner?.goal?.language ?: "en"), Prompts.HINT_BUDGET.maxNewTokens) { hintText += it }
        busy = false
    }

    fun submitTeachBack(explanation: String) = viewModelScope.launch {
        val sid = practiceSkill ?: return@launch; val p = pack ?: return@launch; val l = learner ?: return@launch
        val skill = p.skill(sid); if (skill.keyPoints.isEmpty() || explanation.isBlank()) return@launch
        busy = true
        val out = llm(Prompts.teachBackGrade(skill, explanation), Prompts.TEACHBACK_BUDGET.maxNewTokens, 0f).text
        val n = skill.keyPoints.take(5).size
        val score = OutputParsers.teachBackScore(out, n)
        teachBackResult = if (score == null) "Couldn't grade that reliably — try again in 2–3 sentences."
        else {
            learner = LearnerModel.recordTeachBack(l, sid, score, today); replan()
            val missed = Regex("KP(\\d+)\\s*[:\\-]\\s*NO", RegexOption.IGNORE_CASE).findAll(out).mapNotNull { skill.keyPoints.getOrNull(it.groupValues[1].toInt() - 1) }.toList()
            "You covered ${(score * n).toInt()}/$n key ideas." + if (missed.isNotEmpty()) "\nRevisit: " + missed.joinToString("; ") else ""
        }
        busy = false
    }

    // ======================================================================= 5. Socratic tutor (KV session reuse)
    fun sendTutor(text: String) = viewModelScope.launch {
        val p = pack ?: return@launch; val l = learner ?: return@launch
        val sid = tutorSkill ?: plan?.path?.firstOrNull { it.status == StepStatus.SCHEDULED }?.skillId ?: p.skills.first().id
        tutorSkill = sid
        val skill = p.skill(sid)
        template.facts = skill.keyPoints
        chat += ChatMsg(true, text)
        val idx = chat.size; chat += ChatMsg(false, "", engine.name)
        busy = true
        val fresh = tutorPrimedFor != sid
        val prompt = if (fresh) Prompts.chat(Prompts.tutorSystem(p, skill, LearnerModel.effectiveMastery(l, sid, today), l.goal), text)
        else Prompts.nextTurn(text)
        var acc = ""
        llm(prompt, Prompts.TUTOR_BUDGET.maxNewTokens, 0.6f, cont = !fresh) { acc += it; chat[idx] = chat[idx].copy(text = acc) }
        tutorPrimedFor = sid
        busy = false
    }

    fun selectTutorSkill(id: String) { if (tutorSkill != id) { tutorSkill = id; tutorPrimedFor = null; chat.clear() } }

    // ======================================================================= 6. benchmark (Arm CPU)
    fun runBenchmark() = viewModelScope.launch {
        if (!engine.isLlama) { bench.clear(); bench += BenchRow("No Llama model loaded", null, ""); return@launch }
        busy = true; bench.clear()
        val p = packs.first()
        val cases = listOf(
            "Goal parsing (JSON)" to (Prompts.goalExtraction("I want to crack JEE physics in 3 months, 2 hours daily, weak in rotation", packs) to 120),
            "Plan narration" to (Prompts.narratePlan(p, LearnerGoal("x", p.id, deadlineDays = 60), Planner.plan(p, LearnerModel.init(p, LearnerGoal("x", p.id, deadlineDays = 60), 0), 0), listOf("learn Vectors 25 min")) to 200),
            "Tutor turn" to (Prompts.chat(Prompts.tutorSystem(p, p.skill("rotation"), 0.4, LearnerGoal("x", p.id)), "Why does a rolling ball have rotational energy?") to 160),
        )
        for ((label, pc) in cases) {
            val r = llm(pc.first, pc.second, 0f)
            bench += BenchRow(label, r.stats, r.text.take(160))
            r.stats?.let { st ->
                val f1 = { v: Double -> String.format(java.util.Locale.US, "%.1f", v) }
                val f2 = { v: Double -> String.format(java.util.Locale.US, "%.2f", v) }
                android.util.Log.i("PathShala/Bench",
                    "$label | ${device.soc} | prompt=${st.promptTokens} gen=${st.generatedTokens} ttft_ms=${st.ttftMs} " +
                    "prefill_tps=${f1(st.prefillTokPerSec)} decode_tps=${f1(st.decodeTokPerSec)} rss_mb=${st.rssMb} " +
                    "cores_busy=${f2(st.coresBusy)} threads=${st.peakThreads} power_w=${st.avgPowerW?.let(f2)} " +
                    "j_per_100tok=${st.joulesPer100Tok?.let(f1)} charging=${st.charging}")
            }
        }
        busy = false
    }

    fun stopGeneration() = engine.stop()

    // ======================================================================= persistence
    private fun persist() {
        learner?.let { stateFile.writeText(json.encodeToString(LearnerState.serializer(), it)) }
    }

    private fun restore() {
        runCatching { if (genPackFile.exists()) generatedPack = json.decodeFromString(SkillPack.serializer(), genPackFile.readText()) }
        runCatching {
            if (stateFile.exists()) {
                learner = json.decodeFromString(LearnerState.serializer(), stateFile.readText())
                if (pack == null) learner = null else { replan(); tab = Tab.PLAN }
            }
        }
    }

    fun resetAll() {
        stateFile.delete(); genPackFile.delete()
        learner = null; plan = null; generatedPack = null; draftGoal = null; goalText = ""; chat.clear(); dayOffset = 0
        tab = Tab.GOAL
    }
}
