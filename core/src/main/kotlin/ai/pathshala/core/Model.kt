package ai.pathshala.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * Domain model for PathShala.
 *
 * A [SkillPack] is a curated (or LLM-drafted) prerequisite graph of skills for one
 * learning goal (e.g. "JEE Main Physics: Mechanics"). Each [Skill] carries a concept
 * card (key points used to ground the LLM), study-time estimate, exam weight and a
 * small calibrated item bank used by the adaptive diagnostic.
 */
@Serializable
data class SkillPack(
    val id: String,
    val title: String,
    val titleHi: String = "",
    val domain: String,                 // exam | career | skill | certification
    val description: String,
    val keywords: List<String> = emptyList(),
    val skills: List<Skill>,
    val verified: Boolean = true,       // false for LLM-generated packs
    val source: String = "PathShala curated pack v1",
) {
    fun skill(id: String): Skill = skillIndex[id] ?: error("Unknown skill $id in pack ${this.id}")
    fun skillOrNull(id: String): Skill? = skillIndex[id]
    @kotlinx.serialization.Transient
    private val skillIndex: Map<String, Skill> = skills.associateBy { it.id }
    val allItems: List<Item> get() = skills.flatMap { it.items }
}

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val nameHi: String = "",
    val summary: String,
    val hours: Double,                  // nominal study hours for a novice
    val weight: Double = 1.0,           // exam / goal importance (relative)
    val prereqs: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),   // concept card used to ground Llama
    val resources: List<String> = emptyList(),   // NCERT / NPTEL / docs pointers (offline text)
    val items: List<Item> = emptyList(),
)

/** Multiple-choice item with BKT-style emission parameters derived from difficulty. */
@Serializable
data class Item(
    val id: String,
    val skillId: String,
    val stem: String,
    val options: List<String>,
    val answer: Int,
    val difficulty: Double = 0.0,       // roughly -2 (easy) .. +2 (hard)
    val explanation: String = "",
    val guess: Double? = null,
    val slip: Double? = null,
) {
    /** P(correct | not mastered). Four-option MCQ with partial-knowledge elimination. */
    val pGuess: Double get() = guess ?: (1.0 / options.size + 0.05).coerceAtMost(0.35)
    /** P(incorrect | mastered). Harder items are slipped more often. */
    val pSlip: Double get() = slip ?: (0.05 + 0.15 / (1.0 + exp(-1.5 * difficulty)))
}

enum class SelfLevel { BEGINNER, INTERMEDIATE, ADVANCED }

/** Structured learner goal. Each field records whether Llama or the rule parser produced it. */
@Serializable
data class LearnerGoal(
    val rawText: String,
    val packId: String,
    val targetSkillIds: List<String> = emptyList(),   // empty = whole pack
    val focusSkillIds: List<String> = emptyList(),    // topics the learner flagged (weighted up in diagnosis)
    val deadlineDays: Int? = null,
    val hoursPerWeek: Double = 6.0,
    val level: SelfLevel = SelfLevel.BEGINNER,
    val interests: List<String> = emptyList(),
    val language: String = "en",                      // "en" | "hi" (Hindi / Hinglish)
    val provenance: Map<String, String> = emptyMap(), // field -> "llama" | "rules" | "user"
)
