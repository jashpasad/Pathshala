package ai.pathshala.core.llm

import ai.pathshala.core.*

/**
 * Prompt library for Llama 3.x Instruct (1B/3B) running under ExecuTorch.
 *
 * Design rules for a 1B model (each learned the hard way, see technical report section 5):
 *  - One job per prompt. Never ask the model to reason about schedules or mastery - the symbolic
 *    engine has already decided; Llama only parses language in, and renders language out.
 *  - Grounding facts go in as a compact list; the model is told it may not add topics.
 *  - Line-oriented output formats ("KEY: value") are far more robust than nested JSON at 1B scale;
 *    JSON is used only for the flat goal object and is always validated + repaired + backed by rules.
 *  - Short outputs (token budgets per task) keep latency and battery predictable.
 *
 * The runner adds BOS itself (numBos = 1), so templates start at the first header token.
 */
object Prompts {
    private const val SH = "<|start_header_id|>"
    private const val EH = "<|end_header_id|>\n\n"
    private const val EOT = "<|eot_id|>"

    fun chat(system: String, user: String): String =
        "${SH}system$EH$system$EOT${SH}user$EH$user$EOT${SH}assistant$EH"

    /** Continuation turn appended to an existing KV cache (numBos = 0). */
    fun nextTurn(user: String): String = "$EOT${SH}user$EH$user$EOT${SH}assistant$EH"

    private fun langRule(lang: String) =
        if (lang == "hi") "Reply in simple Hindi (Devanagari). Keep technical terms in English."
        else "Reply in simple English."

    // ---------------------------------------------------------------- 1. goal understanding
    data class Budget(val maxNewTokens: Int)
    val GOAL_BUDGET = Budget(120)

    fun goalExtraction(userText: String, packs: List<SkillPack>): String {
        val catalogue = packs.joinToString("\n") { "- ${it.id}: ${it.title}" }
        val system = """You convert a learner's message into JSON. Output only one JSON object, nothing else.
Fields:
"pack": one id from the catalogue, or "none"
"deadline_days": integer days until the exam or goal date, or null
"hours_per_week": number, or null
"level": "beginner" | "intermediate" | "advanced"
"interests": list of short hobby words
"focus": list of topic names the learner mentions

Catalogue:
$catalogue"""
        val shot = """Example message: I want to crack JEE Mains physics in 3 months, I can study 2 hours daily, weak in rotation. I love cricket.
Example JSON: {"pack":"jee_mechanics","deadline_days":90,"hours_per_week":14,"level":"intermediate","interests":["cricket"],"focus":["rotation"]}

Message: $userText
JSON:"""
        return chat(system, shot)
    }

    // ---------------------------------------------------------------- 2. clarifying question
    val CLARIFY_BUDGET = Budget(60)
    fun clarify(missing: List<String>, lang: String): String = chat(
        "You are PathShala, a friendly study coach. Ask ONE short question to learn the missing details. ${langRule(lang)}",
        "Missing details: ${missing.joinToString(", ")}. Ask one question.",
    )

    // ---------------------------------------------------------------- 3. plan narration (grounded)
    val NARRATE_BUDGET = Budget(220)
    fun narratePlan(pack: SkillPack, goal: LearnerGoal, plan: StudyPlan, todayLines: List<String>): String {
        val sched = plan.path.filter { it.status == StepStatus.SCHEDULED }.take(6)
            .joinToString("\n") { "- ${pack.skill(it.skillId).name}: " + it.reasons.take(2).joinToString("; ") { r -> Reasons.render(r, pack) } }
        val deferred = plan.path.filter { it.status == StepStatus.DEFERRED }.joinToString(", ") { pack.skill(it.skillId).name }
        val mastered = plan.path.filter { it.status == StepStatus.MASTERED }.joinToString(", ") { pack.skill(it.skillId).name }
        val facts = buildString {
            appendLine("Goal: ${pack.title}${goal.deadlineDays?.let { ", exam in $it days" } ?: ""}, ${goal.hoursPerWeek} hours/week.")
            appendLine("Readiness now: ${(plan.currentScore * 100).toInt()}%. Projected if plan followed: ${(plan.projectedScore * 100).toInt()}%.")
            if (mastered.isNotBlank()) appendLine("Already strong: $mastered.")
            appendLine("Next topics in order:\n$sched")
            if (plan.triage) appendLine("Not enough time for everything. Deferred: $deferred.")
            appendLine("Today: ${todayLines.joinToString("; ")}.")
            if (goal.interests.isNotEmpty()) appendLine("Learner enjoys: ${goal.interests.joinToString(", ")}.")
        }
        return chat(
            """You are PathShala, an encouraging study coach. Explain the study plan below to the learner in 4 to 6 short sentences.
Use ONLY the facts given. Do not invent topics, numbers or dates. Mention why the first topic comes first. ${langRule(goal.language)}""",
            facts,
        )
    }

    // ---------------------------------------------------------------- 4. Socratic tutor (grounded)
    val TUTOR_BUDGET = Budget(200)
    fun tutorSystem(pack: SkillPack, skill: Skill, mastery: Double, goal: LearnerGoal): String = """You are PathShala, a patient Socratic tutor for "${skill.name}" (${pack.title}).
Learner mastery of this topic: ${(mastery * 100).toInt()}%. ${if (goal.interests.isNotEmpty()) "Use examples from: ${goal.interests.joinToString(", ")}." else ""}
Concept card (the ONLY facts you may rely on):
${skill.keyPoints.joinToString("\n") { "- $it" }}
Rules: Guide with questions and hints; never just hand over a final answer to a problem the learner is solving.
Ask one question at a time. Keep replies under 90 words. If something is outside the card, say you are not sure. ${langRule(goal.language)}"""

    // ---------------------------------------------------------------- 5. hint ladder for a practice item
    val HINT_BUDGET = Budget(90)
    fun hint(skill: Skill, item: Item, level: Int, lang: String): String {
        val ladder = when (level) {
            1 -> "Give a one-sentence conceptual nudge. Do not mention any option or the answer."
            2 -> "Name the key idea or formula needed, in at most two sentences. Do not reveal the answer."
            else -> "Show the first step of the working, then stop. Do not state the final answer."
        }
        return chat(
            "You are a tutor giving a hint. ${langRule(lang)}\nRelevant facts:\n" + skill.keyPoints.joinToString("\n") { "- $it" },
            "Question: ${item.stem}\nOptions: ${item.options.joinToString(" | ")}\n$ladder",
        )
    }

    // ---------------------------------------------------------------- 6. teach-back (Feynman) grading
    val TEACHBACK_BUDGET = Budget(80)
    fun teachBackGrade(skill: Skill, explanation: String): String {
        val kps = skill.keyPoints.take(5).withIndex().joinToString("\n") { (i, k) -> "KP${i + 1}: $k" }
        return chat(
            "You check whether a student's explanation covers key points. For each key point answer YES or NO. Output exactly one line per key point like 'KP1: YES'. Nothing else.",
            "$kps\n\nStudent explanation: $explanation",
        )
    }

    // ---------------------------------------------------------------- 7. generative pack for open goals
    val PACKGEN_BUDGET = Budget(320)
    fun generatePack(goalText: String): String = chat(
        """You design learning roadmaps. List 8 to 12 topics for the goal, from basic to advanced.
One topic per line, exactly in this format:
<number> | <topic name> | <hours> | <prerequisite numbers separated by commas, or ->
Nothing else.""",
        "Example goal: learn basic Excel for office work\n1 | Spreadsheet basics | 2 | -\n2 | Formulas and references | 3 | 1\n3 | Charts | 2 | 2\n\nGoal: $goalText",
    )
}
