package ai.pathshala.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Graph-CAT: adaptive skill-gap diagnosis on a prerequisite Bayesian network.
 *
 * Model
 *  - Each skill s has latent binary mastery m_s.
 *  - Prerequisite network (noisy-AND with leak):
 *        P(m_s = 1 | all parents mastered)  = q            (prior base rate, from self-reported level)
 *        P(m_s = 1 | some parent unmastered) = leak        (people do learn things out of order)
 *  - Item i on skill s: P(correct | m_s=1) = 1 - slip_i,  P(correct | m_s=0) = guess_i  (BKT emission)
 *
 * Inference: Sequential Monte Carlo (particle filter) with resample-move rejuvenation by an exact
 * Gibbs sweep over the network, so beliefs stay well-calibrated as evidence accumulates.
 *
 * Item selection: pick the item that minimises the expected posterior entropy summed over ALL skills
 * (expected information gain on the whole graph). Because answers propagate through prerequisite
 * edges, one well-chosen question resolves several skills at once - this is what cuts test length.
 *
 * Cost per selection = O(items x particles x skills), approx 1M flops for a 12-skill pack: <5 ms on a
 * Cortex-A7x core, so the diagnostic never needs the LLM or the network.
 */
class GraphDiagnostic(
    val pack: SkillPack,
    level: SelfLevel = SelfLevel.BEGINNER,
    private val numParticles: Int = 3000,
    private val leak: Double = 0.12,
    seed: Long = 7L,
    /** Optional per-skill importance used to weight the entropy objective (e.g. goal targets). */
    private val skillImportance: Map<String, Double> = emptyMap(),
    /** If false the network edges are ignored (independent skills) - used as an ablation baseline. */
    private val useGraph: Boolean = true,
) {
    val graph = SkillGraph(pack)
    private val n = graph.n
    private val rnd = Random(seed)
    private val baseRate = when (level) {
        SelfLevel.BEGINNER -> 0.3
        SelfLevel.INTERMEDIATE -> 0.55
        SelfLevel.ADVANCED -> 0.75
    }
    /**
     * Depth-calibrated conditional priors. A naive constant q makes deep skills look almost never
     * mastered (q^depth compounding under noisy-AND). Instead we solve, in topological order, for the
     * q_s that makes each skill's *marginal* prior equal baseRate x 0.9^layer, using the particle
     * population itself (Monte Carlo calibration).
     */
    private val q = DoubleArray(n)

    // particles[k][s] in {0,1}; weights normalised to sum 1
    private var particles = Array(numParticles) { BooleanArray(n) }
    private var weights = DoubleArray(numParticles) { 1.0 / numParticles }
    private val asked = mutableListOf<Pair<Item, Boolean>>()
    private val importance = DoubleArray(n) { skillImportance[graph.order[it].id] ?: 1.0 }

    init {
        val layers = graph.layers()
        for (sIdx in 0 until n) {
            val target = baseRate * Math.pow(0.9, layers.getValue(graph.order[sIdx].id).toDouble())
            if (!useGraph || graph.parents[sIdx].isEmpty()) { q[sIdx] = target; }
            else {
                val f = particles.count { st -> graph.parents[sIdx].all { st[it] } }.toDouble() / numParticles
                q[sIdx] = if (f < 1e-3) 0.9 else ((target - leak * (1 - f)) / f).coerceIn(0.05, 0.97)
            }
            for (k in 0 until numParticles) particles[k][sIdx] = rnd.nextDouble() < priorProb(sIdx, particles[k])
        }
    }

    val history: List<Pair<Item, Boolean>> get() = asked
    val questionsAsked: Int get() = asked.size

    private fun priorProb(s: Int, state: BooleanArray): Double {
        if (!useGraph) return q[s]
        val ps = graph.parents[s]
        for (p in ps) if (!state[p]) return leak
        return q[s]
    }

    private fun emission(item: Item, mastered: Boolean, correct: Boolean): Double {
        val pc = if (mastered) 1.0 - item.pSlip else item.pGuess
        return if (correct) pc else 1.0 - pc
    }

    /** Posterior P(m_s = 1) for every skill, keyed by skill id. */
    fun marginals(): Map<String, Double> {
        val m = marginalArray()
        return graph.order.indices.associate { graph.order[it].id to m[it] }
    }

    private fun marginalArray(): DoubleArray {
        val m = DoubleArray(n)
        for (k in 0 until numParticles) {
            val w = weights[k]; val st = particles[k]
            for (s in 0 until n) if (st[s]) m[s] += w
        }
        return m
    }

    /** Sum of binary entropies (bits) over skills, importance-weighted: the "uncertainty remaining". */
    fun uncertainty(): Double {
        val m = marginalArray()
        var h = 0.0
        for (s in 0 until n) h += importance[s] * binEntropy(m[s])
        return h
    }

    fun maxUncertainty(): Double = importance.sum()

    /** Choose the next most informative unasked item, or null if the item bank is exhausted. */
    fun nextItem(): Item? {
        val askedIds = asked.map { it.first.id }.toSet()
        val candidates = pack.allItems.filter { it.id !in askedIds }
        if (candidates.isEmpty()) return null
        var best: Item? = null
        var bestScore = Double.MAX_VALUE
        val m1 = DoubleArray(n); val m0 = DoubleArray(n)
        for (item in candidates) {
            val s = graph.index.getValue(item.skillId)
            java.util.Arrays.fill(m1, 0.0); java.util.Arrays.fill(m0, 0.0)
            var p1 = 0.0
            for (k in 0 until numParticles) {
                val st = particles[k]
                val l1 = weights[k] * emission(item, st[s], true)
                val l0 = weights[k] - l1
                p1 += l1
                for (j in 0 until n) if (st[j]) { m1[j] += l1; m0[j] += l0 }
            }
            val p0 = 1.0 - p1
            var h1 = 0.0; var h0 = 0.0
            for (j in 0 until n) {
                h1 += importance[j] * binEntropy(if (p1 > 1e-12) m1[j] / p1 else 0.0)
                h0 += importance[j] * binEntropy(if (p0 > 1e-12) m0[j] / p0 else 0.0)
            }
            // Expected posterior entropy; tiny tie-break prefers easier items first (less test anxiety)
            val score = p1 * h1 + p0 * h0 + 1e-4 * item.difficulty
            if (score < bestScore) { bestScore = score; best = item }
        }
        return best
    }

    /** Expected information gain (bits) of the best next item - used by the stopping rule. */
    fun expectedGainOfNext(): Double {
        val item = nextItem() ?: return 0.0
        val before = uncertainty()
        val s = graph.index.getValue(item.skillId)
        val m1 = DoubleArray(n); val m0 = DoubleArray(n); var p1 = 0.0
        for (k in 0 until numParticles) {
            val st = particles[k]
            val l1 = weights[k] * emission(item, st[s], true); val l0 = weights[k] - l1
            p1 += l1
            for (j in 0 until n) if (st[j]) { m1[j] += l1; m0[j] += l0 }
        }
        var h = 0.0
        for (j in 0 until n) {
            h += importance[j] * (p1 * binEntropy(m1[j] / max(p1, 1e-12)) +
                (1 - p1) * binEntropy(m0[j] / max(1 - p1, 1e-12)))
        }
        return before - h
    }

    /** Incorporate an answer: reweight, and resample-move when the effective sample size collapses. */
    fun observe(item: Item, correct: Boolean) {
        asked += item to correct
        val s = graph.index.getValue(item.skillId)
        var total = 0.0
        for (k in 0 until numParticles) {
            weights[k] *= emission(item, particles[k][s], correct)
            total += weights[k]
        }
        for (k in 0 until numParticles) weights[k] /= total
        val ess = 1.0 / weights.sumOf { it * it }
        if (ess < numParticles / 2.0) resampleMove()
    }

    private fun resampleMove() {
        // Systematic resampling
        val newP = Array(numParticles) { BooleanArray(n) }
        val step = 1.0 / numParticles
        var u = rnd.nextDouble() * step
        var c = weights[0]; var i = 0
        for (k in 0 until numParticles) {
            while (u > c && i < numParticles - 1) { i++; c += weights[i] }
            System.arraycopy(particles[i], 0, newP[k], 0, n)
            u += step
        }
        particles = newP
        weights = DoubleArray(numParticles) { 1.0 / numParticles }
        // Rejuvenation: one exact Gibbs sweep per particle targets the full posterior.
        val obsBySkill = Array(n) { mutableListOf<Pair<Item, Boolean>>() }
        for ((it, y) in asked) obsBySkill[graph.index.getValue(it.skillId)] += it to y
        for (k in 0 until numParticles) gibbsSweep(particles[k], obsBySkill)
    }

    private fun gibbsSweep(st: BooleanArray, obs: Array<MutableList<Pair<Item, Boolean>>>) {
        for (s in 0 until n) {
            var w1 = 1.0; var w0 = 1.0
            val pr = priorProb(s, st)
            w1 *= pr; w0 *= 1 - pr
            if (useGraph) for (c in graph.children[s]) {
                val old = st[s]
                st[s] = true;  val pc1 = priorProb(c, st)
                st[s] = false; val pc0 = priorProb(c, st)
                st[s] = old
                w1 *= if (st[c]) pc1 else 1 - pc1
                w0 *= if (st[c]) pc0 else 1 - pc0
            }
            for ((it, y) in obs[s]) { w1 *= emission(it, true, y); w0 *= emission(it, false, y) }
            st[s] = rnd.nextDouble() < w1 / (w1 + w0)
        }
    }

    /**
     * Stopping rule: stop when every skill is confidently classified, the best question is worth
     * less than [minGainBits], or [maxQuestions] is reached.
     */
    fun shouldStop(maxQuestions: Int = 12, minGainBits: Double = 0.03, confident: Double = 0.85): Boolean {
        if (asked.size >= maxQuestions) return true
        val m = marginalArray()
        if (m.all { it <= 1 - confident || it >= confident }) return true
        return asked.size >= 3 && expectedGainOfNext() < minGainBits
    }

    companion object {
        fun binEntropy(p: Double): Double {
            if (p <= 1e-12 || p >= 1 - 1e-12) return 0.0
            return -(p * ln(p) + (1 - p) * ln(1 - p)) / ln(2.0)
        }
    }
}
