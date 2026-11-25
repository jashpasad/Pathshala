package ai.pathshala.core

import kotlinx.serialization.Serializable

/**
 * Per-learner state, persisted on-device as JSON. Event-sourced: every observation is appended to
 * [events], so the model can be rebuilt, audited by the learner ("why does it think I'm weak here?")
 * and, in Phase 2, used to fit personal BKT/FSRS parameters.
 */
@Serializable
data class SkillState(
    val pMastery: Double,
    val card: FsrsCard? = null,
    val attempts: Int = 0,
    val correct: Int = 0,
    val recentWrong: Int = 0,       // consecutive recent errors (root-cause remediation trigger)
    val minutesStudied: Int = 0,
)

@Serializable
data class LearningEvent(
    val day: Int,
    val skillId: String,
    val kind: String,               // diagnostic | practice | study | teachback | review
    val correct: Boolean? = null,
    val minutes: Int = 0,
    val detail: String = "",
)

@Serializable
data class LearnerState(
    val packId: String,
    val goal: LearnerGoal,
    val skills: Map<String, SkillState>,
    val events: List<LearningEvent> = emptyList(),
    val diagnosticDone: Boolean = false,
    val startDay: Int,
)

/**
 * Bayesian Knowledge Tracing (Corbett & Anderson, 1994) with FSRS forgetting on top.
 * Pure functions over immutable state, so UI, tests and simulator share identical behaviour.
 */
object LearnerModel {
    const val MASTERY = 0.8
    const val LEARN_RATE = 0.15        // P(T): chance of learning from one practice opportunity
    const val STUDY_LEARN_RATE = 0.10  // per 25-minute study block without a graded answer

    fun init(pack: SkillPack, goal: LearnerGoal, today: Int): LearnerState {
        val prior = when (goal.level) {
            SelfLevel.BEGINNER -> 0.2; SelfLevel.INTERMEDIATE -> 0.45; SelfLevel.ADVANCED -> 0.65
        }
        return LearnerState(pack.id, goal, pack.skills.associate { it.id to SkillState(prior) }, startDay = today)
    }

    fun applyDiagnostic(state: LearnerState, marginals: Map<String, Double>, history: List<Pair<Item, Boolean>>, today: Int): LearnerState {
        val skills = state.skills.mapValues { (id, s) ->
            val p = marginals[id] ?: s.pMastery
            val obs = history.filter { it.first.skillId == id }
            s.copy(
                pMastery = p.coerceIn(0.02, 0.98),
                attempts = s.attempts + obs.size,
                correct = s.correct + obs.count { it.second },
                // diagnosed mastery = prior long-term knowledge: start with the more stable EASY memory state
                card = if (p >= MASTERY) Fsrs.newCard(Rating.EASY, today) else s.card,
            )
        }
        val ev = history.map { (it, y) -> LearningEvent(today, it.skillId, "diagnostic", y, detail = it.id) }
        return state.copy(skills = skills, events = state.events + ev, diagnosticDone = true)
    }

    /** BKT posterior after a graded answer, followed by the learning transition and FSRS update. */
    fun recordAnswer(state: LearnerState, item: Item, correct: Boolean, today: Int): LearnerState {
        val s = state.skills.getValue(item.skillId)
        val p = effectiveMastery(s, today)
        val pc = if (correct) 1 - item.pSlip else item.pSlip
        val pw = if (correct) item.pGuess else 1 - item.pGuess
        val post = p * pc / (p * pc + (1 - p) * pw)
        val next = (post + (1 - post) * LEARN_RATE).coerceIn(0.02, 0.99)
        val card = when {
            s.card != null -> Fsrs.review(s.card, if (correct) Rating.GOOD else Rating.AGAIN, today)
            next >= MASTERY -> Fsrs.newCard(Rating.GOOD, today)
            else -> null
        }
        val ns = s.copy(
            pMastery = next, card = card, attempts = s.attempts + 1,
            correct = s.correct + if (correct) 1 else 0,
            recentWrong = if (correct) 0 else s.recentWrong + 1,
        )
        return state.copy(
            skills = state.skills + (item.skillId to ns),
            events = state.events + LearningEvent(today, item.skillId, "practice", correct, detail = item.id),
        )
    }

    /** Soft evidence from Llama-graded teach-back (Feynman) explanations: score in [0,1], low weight. */
    fun recordTeachBack(state: LearnerState, skillId: String, score: Double, today: Int): LearnerState {
        val s = state.skills.getValue(skillId)
        val lr = 2.0 * (score - 0.5)           // -1..1
        val p = (s.pMastery + 0.15 * lr * (if (lr > 0) 1 - s.pMastery else s.pMastery)).coerceIn(0.02, 0.98)
        return state.copy(
            skills = state.skills + (skillId to s.copy(pMastery = p)),
            events = state.events + LearningEvent(today, skillId, "teachback", score >= 0.6, detail = "%.2f".format(score)),
        )
    }

    fun recordStudy(state: LearnerState, skillId: String, minutes: Int, today: Int): LearnerState {
        val s = state.skills.getValue(skillId)
        val blocks = minutes / 25.0
        var p = s.pMastery
        repeat(blocks.toInt().coerceAtLeast(1)) { p += (1 - p) * STUDY_LEARN_RATE }
        return state.copy(
            skills = state.skills + (skillId to s.copy(pMastery = p.coerceAtMost(0.95), minutesStudied = s.minutesStudied + minutes)),
            events = state.events + LearningEvent(today, skillId, "study", minutes = minutes),
        )
    }

    /** "Can you still do it today?" = mastery x memory retrievability (FSRS). */
    fun effectiveMastery(s: SkillState, today: Int): Double {
        val r = s.card?.let { Fsrs.retrievability(it, today) } ?: 1.0
        return (s.pMastery * (0.25 + 0.75 * r)).coerceIn(0.0, 1.0)
    }

    fun effectiveMastery(state: LearnerState, skillId: String, today: Int) =
        effectiveMastery(state.skills.getValue(skillId), today)
}
