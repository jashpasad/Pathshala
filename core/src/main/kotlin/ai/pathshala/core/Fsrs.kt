package ai.pathshala.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-4.5 memory model (Free Spaced Repetition Scheduler; lineage: Ye, Su & Cao, KDD 2022, SSP-MMC
 * from MaiMemo). Three-component memory state (difficulty D, stability S, retrievability R) with a
 * power-law forgetting curve. Default weights are the published FSRS-4.5 defaults; Phase 2 will fit
 * them per learner on-device from the review log (the optimiser is a few hundred lines of gradient
 * descent - small enough to run during charging).
 */
@Serializable
data class FsrsCard(val stability: Double, val difficulty: Double, val lastReviewDay: Int, val reps: Int = 1)

enum class Rating(val g: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

object Fsrs {
    val w = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031,
        1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
    )
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0

    fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)

    fun retrievability(card: FsrsCard, today: Int): Double =
        retrievability((today - card.lastReviewDay).coerceAtLeast(0).toDouble(), card.stability)

    /** Days until retrievability falls to [retention]. */
    fun interval(stability: Double, retention: Double = 0.9): Double =
        stability / FACTOR * (retention.pow(1.0 / DECAY) - 1.0)

    private fun d0(g: Int) = (w[4] - (g - 3) * w[5]).coerceIn(1.0, 10.0)

    fun newCard(rating: Rating, today: Int) =
        FsrsCard(stability = w[rating.g - 1], difficulty = d0(rating.g), lastReviewDay = today)

    fun review(card: FsrsCard, rating: Rating, today: Int): FsrsCard {
        val g = rating.g
        val r = retrievability(card, today)
        val d = card.difficulty
        val nextD = (w[7] * d0(3) + (1 - w[7]) * (d - w[6] * (g - 3))).coerceIn(1.0, 10.0)
        val nextS = if (rating == Rating.AGAIN) {
            w[11] * d.pow(-w[12]) * ((card.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        } else {
            val hard = if (rating == Rating.HARD) w[15] else 1.0
            val easy = if (rating == Rating.EASY) w[16] else 1.0
            card.stability * (1 + exp(w[8]) * (11 - d) * card.stability.pow(-w[9]) *
                (exp(w[10] * (1 - r)) - 1) * hard * easy)
        }
        return FsrsCard(nextS.coerceAtLeast(0.1), nextD, today, card.reps + 1)
    }
}
