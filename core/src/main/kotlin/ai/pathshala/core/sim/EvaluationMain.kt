package ai.pathshala.core.sim

import ai.pathshala.core.*
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Offline evaluation harness. Drives the SAME engine classes that ship in the Android app with
 * synthetic learners drawn from a generative model that deliberately differs from the engine's
 * assumptions (model-mismatch): continuous latent ability, a leakier prerequisite structure and
 * per-item guess/slip rates the engine never sees. Results are therefore a robustness test, not a
 * self-fulfilling one.
 *
 * Experiments
 *  E1  Diagnostic efficiency - classification accuracy of true mastery vs. number of questions for
 *      Graph-CAT (ours) vs. ablations (no graph; random item order) vs. a fixed placement test.
 *  E2  Stopping rule - questions used and accuracy when Graph-CAT stops itself.
 *  E3  Planning - weighted exam readiness at the deadline for PathShala's adaptive planner vs. a
 *      linear syllabus vs. diagnostic+linear, under a tight and a comfortable time budget.
 *
 * Usage: ./gradlew :core:run --args="eval/results"
 */
class SyntheticLearner(val pack: SkillPack, rnd: Random, itemParams: Map<String, Pair<Double, Double>>) {
    val g = SkillGraph(pack)
    val theta = gauss(rnd)
    val mastered = BooleanArray(g.n)
    val reportedLevel: SelfLevel
    private val params = itemParams

    init {
        val layers = g.layers()
        for (i in 0 until g.n) {
            val s = g.order[i]
            val d = -0.6 + 0.35 * layers.getValue(s.id) + 0.3 * gauss(rnd)
            var m = theta + 0.6 * gauss(rnd) > d
            if (m && g.parents[i].any { !mastered[it] }) m = rnd.nextDouble() < 0.3   // leakier than engine's 0.12
            mastered[i] = m
        }
        val r = theta + 0.7 * gauss(rnd)
        reportedLevel = when { r > 0.8 -> SelfLevel.ADVANCED; r > -0.3 -> SelfLevel.INTERMEDIATE; else -> SelfLevel.BEGINNER }
    }

    fun isMastered(id: String) = mastered[g.index.getValue(id)]

    fun answer(item: Item, rnd: Random, knows: Boolean = isMastered(item.skillId)): Boolean {
        val (gs, sl) = params.getValue(item.id)
        return if (knows) rnd.nextDouble() > sl else rnd.nextDouble() < gs
    }

    companion object {
        fun gauss(r: Random): Double {
            var u = 0.0; var v = 0.0
            while (u == 0.0) u = r.nextDouble(); while (v == 0.0) v = r.nextDouble()
            return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
        /** True item parameters, hidden from the engine. */
        fun itemParams(pack: SkillPack, rnd: Random) = pack.allItems.associate {
            it.id to ((0.15 + 0.2 * rnd.nextDouble()) to (0.03 + 0.17 * rnd.nextDouble()))
        }
    }
}

fun accuracy(est: Map<String, Double>, l: SyntheticLearner): Double =
    l.pack.skills.count { (est.getValue(it.id) >= 0.5) == l.isMastered(it.id) }.toDouble() / l.pack.skills.size

fun brier(est: Map<String, Double>, l: SyntheticLearner): Double =
    l.pack.skills.sumOf { val y = if (l.isMastered(it.id)) 1.0 else 0.0; (est.getValue(it.id) - y).let { d -> d * d } } / l.pack.skills.size

// ------------------------------------------------------------------------------------------- E1/E2
fun runDiagnostic(pack: SkillPack, nLearners: Int, maxQ: Int, out: File, seed: Long) {
    val strategies = listOf("graph_cat", "indep_cat", "graph_random", "fixed_form")
    val acc = Array(strategies.size) { DoubleArray(maxQ + 1) }
    val bri = Array(strategies.size) { DoubleArray(maxQ + 1) }
    var stopQ = 0.0; var stopAcc = 0.0
    val rndItems = Random(seed)
    val params = SyntheticLearner.itemParams(pack, rndItems)
    val graph = SkillGraph(pack)
    for (n in 0 until nLearners) {
        val lr = Random(seed * 1000 + n)
        val learner = SyntheticLearner(pack, lr, params)
        // pre-draw answers so every strategy sees the same responses for the same item
        val answers = pack.allItems.associate { it.id to learner.answer(it, lr) }
        for ((si, strat) in strategies.withIndex()) {
            val rnd = Random(seed + n * 7L + si)
            if (strat == "fixed_form") {
                // Classic placement test: items in syllabus order, one per skill, then second items.
                val order = graph.order.flatMap { s -> s.items.take(1) } + graph.order.flatMap { s -> s.items.drop(1) }
                val q0 = if (learner.reportedLevel == SelfLevel.BEGINNER) 0.35 else if (learner.reportedLevel == SelfLevel.INTERMEDIATE) 0.6 else 0.8
                for (k in 0..maxQ) {
                    val seen = order.take(k)
                    val est = pack.skills.associate { s ->
                        val obs = seen.filter { it.skillId == s.id }
                        s.id to if (obs.isEmpty()) q0 else obs.count { answers.getValue(it.id) }.toDouble() / obs.size
                    }
                    acc[si][k] += accuracy(est, learner); bri[si][k] += brier(est, learner)
                }
                continue
            }
            val diag = GraphDiagnostic(pack, learner.reportedLevel, numParticles = 1500, seed = seed + n,
                useGraph = strat != "indep_cat")
            for (k in 0..maxQ) {
                val est = diag.marginals()
                acc[si][k] += accuracy(est, learner); bri[si][k] += brier(est, learner)
                if (k == maxQ) break
                val item = if (strat == "graph_random") {
                    val asked = diag.history.map { it.first.id }.toSet()
                    pack.allItems.filter { it.id !in asked }.randomOrNull(rnd)
                } else diag.nextItem()
                item ?: break
                diag.observe(item, answers.getValue(item.id))
            }
        }
        // E2: self-stopping Graph-CAT
        val d2 = GraphDiagnostic(pack, learner.reportedLevel, numParticles = 1500, seed = seed + n)
        while (!d2.shouldStop(maxQuestions = 12)) {
            val it = d2.nextItem() ?: break
            d2.observe(it, answers.getValue(it.id))
        }
        stopQ += d2.questionsAsked; stopAcc += accuracy(d2.marginals(), learner)
    }
    out.printWriter().use { w ->
        w.println("pack,strategy,questions,accuracy,brier")
        for ((si, s) in strategies.withIndex()) for (k in 0..maxQ)
            w.println("${pack.id},$s,$k,${"%.4f".format(acc[si][k] / nLearners)},${"%.4f".format(bri[si][k] / nLearners)}")
    }
    File(out.parentFile, "stop_${pack.id}.txt").writeText(
        "pack=${pack.id} learners=$nLearners avg_questions=${"%.2f".format(stopQ / nLearners)} accuracy_at_stop=${"%.4f".format(stopAcc / nLearners)}\n")
    println("E1/E2 ${pack.id}: " + strategies.indices.joinToString { i ->
        "${strategies[i]} acc@6=${"%.3f".format(acc[i][6] / nLearners)} acc@10=${"%.3f".format(acc[i][10] / nLearners)}"
    } + " | self-stop q=${"%.2f".format(stopQ / nLearners)} acc=${"%.3f".format(stopAcc / nLearners)}")
}

// ------------------------------------------------------------------------------------------- E3
/** Ground-truth study dynamics: learning needs true prerequisites; memory decays without review. */
class StudyWorld(val learner: SyntheticLearner, val rnd: Random) {
    val pack = learner.pack
    val g = learner.g
    val known = learner.mastered.copyOf()
    val stability = DoubleArray(g.n) { 30.0 }
    val lastSeen = IntArray(g.n) { -10 }
    private val preKnown = learner.mastered.copyOf()
    var wastedMinutes = 0
    var totalMinutes = 0

    fun knowsNow(i: Int, day: Int) = known[i] && rnd.nextDouble() < exp(-(day - lastSeen[i]) / stability[i])

    fun study(id: String, day: Int, minutes: Int, review: Boolean) {
        val i = g.index.getValue(id)
        totalMinutes += minutes
        val s = pack.skill(id)
        if (known[i] && exp(-(day - lastSeen[i]) / stability[i]) > 0.5) {
            // "waste" = re-learning (not reviewing) something the learner already knew on day 0
            if (!review && preKnown[i]) wastedMinutes += minutes
            stability[i] = min(180.0, stability[i] * (if (review) 2.5 else 1.5)); lastSeen[i] = day
            return
        }
        val nominalBlocks = s.hours * 60.0 / 25.0
        val p = (1.4 / nominalBlocks) * (minutes / 25.0) * (if (g.parents[i].all { known[it] }) 1.0 else 0.25)
        if (known[i] || rnd.nextDouble() < p) {
            if (!known[i]) stability[i] = 4.0 else stability[i] = max(4.0, stability[i])
            known[i] = true; lastSeen[i] = day
        }
    }

    /** Expected weighted readiness on exam day (probability each skill is still retained). */
    fun readiness(day: Int, closure: Set<String>): Double {
        val w = closure.sumOf { pack.skill(it).weight }
        return closure.sumOf { id ->
            val i = g.index.getValue(id)
            if (!known[i]) 0.0 else pack.skill(id).weight * exp(-(day - lastSeen[i]) / stability[i])
        } / w
    }
}

fun runPlanning(pack: SkillPack, nLearners: Int, deadline: Int, hoursPerWeek: Double, out: File, seed: Long): List<String> {
    val policies = listOf("linear_syllabus", "diagnostic_linear", "pathshala")
    val score = DoubleArray(3); val waste = DoubleArray(3)
    val params = SyntheticLearner.itemParams(pack, Random(seed))
    val dailyMin = (hoursPerWeek * 60 / 7).toInt()
    val all = pack.skills.map { it.id }.toSet()
    for (n in 0 until nLearners) {
        for ((pi, pol) in policies.withIndex()) {
            val lr = Random(seed * 31 + n)                  // same learner for all policies
            val learner = SyntheticLearner(pack, lr, params)
            val world = StudyWorld(learner, Random(seed * 77 + n * 3 + pi))
            val g = learner.g
            val goal = LearnerGoal("sim", pack.id, deadlineDays = deadline, hoursPerWeek = hoursPerWeek, level = learner.reportedLevel)
            var state = LearnerModel.init(pack, goal, 0)
            if (pol != "linear_syllabus") {
                val diag = GraphDiagnostic(pack, learner.reportedLevel, numParticles = 1500, seed = seed + n)
                val r = Random(seed + 99 * n)
                while (!diag.shouldStop(maxQuestions = 12)) {
                    val it = diag.nextItem() ?: break
                    diag.observe(it, learner.answer(it, r, world.knowsNow(g.index.getValue(it.skillId), 0)))
                }
                state = LearnerModel.applyDiagnostic(state, diag.marginals(), diag.history, 0)
            }
            // static queues for the two baselines
            val queue = ArrayDeque<String>()
            if (pol != "pathshala") for (s in g.order) {
                if (pol == "diagnostic_linear" && state.skills.getValue(s.id).pMastery >= LearnerModel.MASTERY) continue
                repeat(max(1, (s.hours * 60 / 25).toInt())) { queue.addLast(s.id) }
            }
            for (day in 0 until deadline) {
                if ((day % 7) == 6) continue                 // weekly rest day for every policy
                if (pol == "pathshala") {
                    val plan = Planner.plan(pack, state, day)
                    var used = 0
                    for (b in plan.blocks.filter { it.day == day }) {
                        if (used + b.minutes > dailyMin + 10) break
                        used += b.minutes
                        val review = b.kind == BlockKind.REVIEW || b.kind == BlockKind.REMEDIATE || b.reasons.any { it.code == "CONSOLIDATE" }
                        world.study(b.skillId, day, b.minutes, review)
                        if (b.kind == BlockKind.LEARN) state = LearnerModel.recordStudy(state, b.skillId, b.minutes, day)
                        else {
                            // checkpoint / review: two graded practice items from the bank
                            val items = pack.skill(b.skillId).items
                            repeat(2) { k ->
                                val item = items[k % items.size]
                                val y = learner.answer(item, world.rnd, world.knowsNow(g.index.getValue(b.skillId), day))
                                state = LearnerModel.recordAnswer(state, item, y, day)
                            }
                        }
                    }
                } else {
                    var used = 0
                    while (used + 25 <= dailyMin && queue.isNotEmpty()) {
                        world.study(queue.removeFirst(), day, 25, false); used += 25
                    }
                }
            }
            score[pi] += world.readiness(deadline, all)
            waste[pi] += world.wastedMinutes.toDouble() / max(1, world.totalMinutes)
        }
    }
    val lines = policies.indices.map { i ->
        "${pack.id},$deadline,$hoursPerWeek,${policies[i]},${"%.4f".format(score[i] / nLearners)},${"%.4f".format(waste[i] / nLearners)}"
    }
    println("E3 ${pack.id} D=$deadline h/w=$hoursPerWeek: " + policies.indices.joinToString { "${policies[it]}=${"%.3f".format(score[it] / nLearners)} (waste ${"%.0f".format(100 * waste[it] / nLearners)}%)" })
    return lines
}

fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "eval/results").apply { mkdirs() }
    val nDiag = args.getOrNull(1)?.toInt() ?: 500
    val nPlan = args.getOrNull(2)?.toInt() ?: 200
    val packs = PackRepository.loadAll()
    val t0 = System.currentTimeMillis()
    for ((i, p) in packs.withIndex()) runDiagnostic(p, nDiag, 20, File(outDir, "diagnostic_${p.id}.csv"), 1000L + i)
    val rows = mutableListOf("pack,deadline_days,hours_per_week,policy,readiness,wasted_share")
    for ((i, p) in packs.withIndex()) {
        rows += runPlanning(p, nPlan, 56, 5.0, outDir, 2000L + i)    // tight: 5 h/week for 8 weeks
        rows += runPlanning(p, nPlan, 56, 10.0, outDir, 3000L + i)   // comfortable: 10 h/week
    }
    File(outDir, "planning.csv").writeText(rows.joinToString("\n") + "\n")
    println("done in ${(System.currentTimeMillis() - t0) / 1000}s -> ${outDir.absolutePath}")
}
