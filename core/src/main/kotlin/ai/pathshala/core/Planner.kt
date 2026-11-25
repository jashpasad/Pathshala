package ai.pathshala.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class BlockKind { LEARN, PRACTICE, REVIEW, REMEDIATE }
enum class StepStatus { SCHEDULED, DEFERRED, MASTERED }

/** Machine-readable justification. Rendered by [Reasons] (templates) and narrated by Llama. */
@Serializable
data class Reason(val code: String, val value: Double = 0.0, val ref: String = "")

@Serializable
data class PlanBlock(val day: Int, val skillId: String, val kind: BlockKind, val minutes: Int, val reasons: List<Reason>)

@Serializable
data class PathStep(
    val skillId: String,
    val status: StepStatus,
    val remainingHours: Double,
    val valuePerHour: Double,
    val reasons: List<Reason>,
)

@Serializable
data class StudyPlan(
    val path: List<PathStep>,
    val blocks: List<PlanBlock>,
    val budgetHours: Double?,
    val neededHours: Double,
    val triage: Boolean,
    val coverage: Double,          // weighted share of the goal reachable by the deadline
    val currentScore: Double,      // weighted effective mastery today
    val projectedScore: Double,    // expected weighted mastery at deadline if plan is followed
    val finishDay: Int?,
    val generatedDay: Int,
)

/**
 * Deadline-aware, prerequisite-respecting study planner.
 *
 *  1. Need set  = goal targets + their ancestors whose effective mastery < tau.
 *  2. Value     = exam weight x knowledge gap  + unlock bonus for gaps in dependent skills.
 *  3. Triage    = if hours needed exceed hours available before the deadline, choose the
 *                 prerequisite-closed subset with the best value per hour (greedy closure knapsack)
 *                 and tell the learner honestly what is deferred and why.
 *  4. Ordering  = list scheduling on the DAG by value density.
 *  5. Calendar  = daily capacity filled with FSRS-due reviews first (capped), root-cause remediation,
 *                 then new learning blocks ending in a checkpoint practice block.
 * Deterministic and ~1 ms, so it re-runs after every event: this is how the plan "adapts over time".
 */
object Planner {
    const val TAU = LearnerModel.MASTERY
    const val BLOCK_MIN = 25
    const val REVIEW_MIN = 15
    const val STUDY_DAYS_PER_WEEK = 6.0
    const val REVIEW_SHARE = 0.35
    private const val HORIZON_NO_DEADLINE = 120

    fun plan(pack: SkillPack, state: LearnerState, today: Int): StudyPlan {
        val g = SkillGraph(pack)
        val goal = state.goal
        val eff = pack.skills.associate { it.id to LearnerModel.effectiveMastery(state, it.id, today) }
        val targets = goal.targetSkillIds.ifEmpty { pack.skills.map { it.id } }.toSet()
        val closure = targets + targets.flatMap { g.ancestors(it) }
        val need = closure.filter { eff.getValue(it) < TAU }.toSet()

        fun remaining(id: String): Double {
            val s = pack.skill(id)
            return s.hours * (0.3 + 0.7 * (1 - eff.getValue(id)))
        }
        val unlock = need.associateWith { id ->
            g.descendants(id).filter { it in need }.sumOf { pack.skill(it).weight * (1 - eff.getValue(it)) }
        }
        fun value(id: String): Double = pack.skill(id).weight * (1 - eff.getValue(id)) + 0.25 * unlock.getValue(id)

        val neededHours = need.sumOf { remaining(it) }
        // deadlineDays is relative to goal creation (state.startDay); compute days actually left
        val daysLeft = goal.deadlineDays?.let { max(1, it - (today - state.startDay)) }
        val horizon = daysLeft ?: HORIZON_NO_DEADLINE
        val budget = daysLeft?.let { goal.hoursPerWeek * it / 7.0 * (1 - REVIEW_SHARE * 0.5) }

        // ---- triage (greedy, prerequisite-closed) ----
        val selected = LinkedHashSet<String>()
        if (budget == null || neededHours <= budget) {
            selected += need
        } else {
            var left = budget
            while (true) {
                var best: String? = null; var bestRatio = -1.0; var bestBundle = emptySet<String>()
                for (s in need - selected) {
                    val bundle = (g.ancestors(s).filter { it in need } + s).toSet() - selected
                    val cost = bundle.sumOf { remaining(it) }
                    if (cost > left + 1e-9) continue
                    val ratio = bundle.sumOf { value(it) } / cost
                    if (ratio > bestRatio) { bestRatio = ratio; best = s; bestBundle = bundle }
                }
                if (best == null) break
                selected += bestBundle
                left -= bestBundle.sumOf { remaining(it) }
            }
        }
        val triage = selected.size < need.size

        // ---- ordering: list scheduling over the DAG ----
        val ordered = mutableListOf<String>()
        val placed = HashSet<String>()
        while (ordered.size < selected.size) {
            val ready = selected.filter { it !in placed && pack.skill(it).prereqs.all { p -> p !in selected || p in placed } }
            val pick = ready.maxByOrNull { value(it) / remaining(it) } ?: break
            ordered += pick; placed += pick
        }

        fun reasonsFor(id: String): List<Reason> {
            val r = mutableListOf<Reason>()
            val s = state.skills.getValue(id)
            r += Reason("GAP", eff.getValue(id))
            val unlocks = g.descendants(id).count { it in need }
            if (unlocks > 0) r += Reason("UNLOCKS", unlocks.toDouble())
            val maxW = pack.skills.maxOf { it.weight }
            if (pack.skill(id).weight >= 0.75 * maxW) r += Reason("HIGH_WEIGHT", pack.skill(id).weight)
            if (s.recentWrong >= 2) r += Reason("RECENT_ERRORS", s.recentWrong.toDouble())
            if (s.card != null && s.pMastery >= TAU) r += Reason("FORGETTING", Fsrs.retrievability(s.card, today))
            return r
        }

        val path = buildList {
            ordered.forEach { add(PathStep(it, StepStatus.SCHEDULED, remaining(it), value(it) / remaining(it), reasonsFor(it))) }
            (need - selected).sortedByDescending { value(it) / remaining(it) }.forEach {
                add(PathStep(it, StepStatus.DEFERRED, remaining(it), value(it) / remaining(it),
                    listOf(Reason("DEFERRED_LOW_VALUE", value(it) / remaining(it))) + reasonsFor(it)))
            }
            g.order.map { it.id }.filter { it in closure && it !in need }.forEach {
                add(PathStep(it, StepStatus.MASTERED, 0.0, 0.0, listOf(Reason("ALREADY_MASTERED", eff.getValue(it)))))
            }
        }

        // ---- calendar ----
        val dayCap = max(BLOCK_MIN, ((goal.hoursPerWeek * 60 / STUDY_DAYS_PER_WEEK) / 5).toInt() * 5)
        val learnQueue = ArrayDeque<Pair<String, BlockKind>>()
        for (id in ordered) {
            val nBlocks = max(1, ceil(remaining(id) * 60 / BLOCK_MIN).toInt())
            repeat(nBlocks - 1) { learnQueue.addLast(id to BlockKind.LEARN) }
            learnQueue.addLast(id to BlockKind.PRACTICE)
        }
        // Root-cause remediation: repeated errors on a skill whose prerequisite looks shaky
        val remediate = pack.skills.filter { state.skills.getValue(it.id).recentWrong >= 2 }.flatMap { s ->
            s.prereqs.filter { eff.getValue(it) < 0.7 }.map { it to s.id }
        }.distinctBy { it.first }

        // Projected memory cards for reviews (assume GOOD reviews when simulating the future)
        val cards = state.skills.filter { it.value.card != null && it.value.pMastery >= TAU }
            .mapValues { it.value.card!! }.toMutableMap()
        val blocks = mutableListOf<PlanBlock>()
        var finishDay: Int? = null
        val lastDay = today + min(horizon, HORIZON_NO_DEADLINE)
        var day = today
        var remediated = false
        while (day <= lastDay && (learnQueue.isNotEmpty() || day < today + 14)) {
            var cap = dayCap
            if (!remediated) {
                for ((p, child) in remediate) {
                    blocks += PlanBlock(day, p, BlockKind.REMEDIATE, BLOCK_MIN, listOf(Reason("ROOT_CAUSE", eff.getValue(p), child)))
                    cap -= BLOCK_MIN
                }
                remediated = true
            }
            val reviewCap = (dayCap * REVIEW_SHARE).toInt()
            var used = 0
            val due = cards.entries.filter { (_, c) -> Fsrs.retrievability(c, day) < 0.9 }
                .sortedBy { (_, c) -> Fsrs.retrievability(c, day) }
            for ((id, c) in due) {
                if (used + REVIEW_MIN > reviewCap || cap < REVIEW_MIN) break
                blocks += PlanBlock(day, id, BlockKind.REVIEW, REVIEW_MIN, listOf(Reason("DUE_REVIEW", Fsrs.retrievability(c, day))))
                cards[id] = Fsrs.review(c, Rating.GOOD, day)
                used += REVIEW_MIN; cap -= REVIEW_MIN
            }
            while (cap >= BLOCK_MIN && learnQueue.isNotEmpty()) {
                val (id, kind) = learnQueue.removeFirst()
                blocks += PlanBlock(day, id, kind, BLOCK_MIN, reasonsFor(id))
                cap -= BLOCK_MIN
                if (kind == BlockKind.PRACTICE) {
                    cards[id] = Fsrs.newCard(Rating.GOOD, day)
                    if (learnQueue.isEmpty()) finishDay = day
                }
            }
            // Path finished (or blocked): use spare time for consolidation practice on the weakest skills
            if (learnQueue.isEmpty() && cap >= BLOCK_MIN) {
                val weakest = closure.filter { it in selected || it !in need }
                    .map { it to (cards[it]?.let { c -> Fsrs.retrievability(c, day) } ?: eff.getValue(it)) }
                    .filter { it.second < 0.95 }.sortedBy { it.second }
                for ((id, r) in weakest) {
                    if (cap < BLOCK_MIN) break
                    blocks += PlanBlock(day, id, BlockKind.PRACTICE, BLOCK_MIN, listOf(Reason("CONSOLIDATE", r)))
                    cards[id] = cards[id]?.let { Fsrs.review(it, Rating.GOOD, day) } ?: Fsrs.newCard(Rating.GOOD, day)
                    cap -= BLOCK_MIN
                }
            }
            // rest day: 7th day of each week has no new learning (spacing + burnout guard)
            day += if ((day - today) % 7 == 5) 2 else 1
        }

        val wSum = closure.sumOf { pack.skill(it).weight }
        val current = closure.sumOf { pack.skill(it).weight * eff.getValue(it) } / wSum
        val projected = closure.sumOf { id ->
            val p = when {
                id in selected -> max(eff.getValue(id), 0.85)
                else -> eff.getValue(id)
            }
            pack.skill(id).weight * p
        } / wSum
        val coverage = closure.sumOf { id -> if (id in selected || id !in need) pack.skill(id).weight else 0.0 } / wSum

        return StudyPlan(path, blocks, budget, neededHours, triage, coverage, current, projected, finishDay, today)
    }
}

/** Deterministic, bilingual rendering of planner reasons (also used as grounding facts for Llama). */
object Reasons {
    fun render(r: Reason, pack: SkillPack, lang: String = "en"): String {
        val hi = lang == "hi"
        val pct = (r.value * 100).toInt()
        return when (r.code) {
            "GAP" -> if (hi) "अभी महारत ~$pct%" else "Current mastery ~$pct%"
            "UNLOCKS" -> if (hi) "${r.value.toInt()} आगे के टॉपिक खोलता है" else "Unlocks ${r.value.toInt()} later topics"
            "HIGH_WEIGHT" -> if (hi) "परीक्षा में ज़्यादा वज़न" else "High exam weight"
            "RECENT_ERRORS" -> if (hi) "हाल में ${r.value.toInt()} गलतियाँ" else "${r.value.toInt()} recent mistakes"
            "FORGETTING" -> if (hi) "याददाश्त ~$pct%" else "Memory strength ~$pct%"
            "DUE_REVIEW" -> if (hi) "दोहराने का समय (याद ~$pct%)" else "Due for review (recall ~$pct%)"
            "ROOT_CAUSE" -> {
                val child = pack.skillOrNull(r.ref)?.name ?: r.ref
                if (hi) "'$child' की गलतियों की जड़" else "Likely root cause of mistakes in '$child'"
            }
            "DEFERRED_LOW_VALUE" -> if (hi) "समय कम है - कम प्राथमिकता" else "Deferred: lower value per hour before deadline"
            "ALREADY_MASTERED" -> if (hi) "पहले से आता है" else "Already mastered"
            "CONSOLIDATE" -> if (hi) "पक्का करने के लिए अभ्यास" else "Consolidation practice (strength ~$pct%)"
            else -> r.code
        }
    }
}
