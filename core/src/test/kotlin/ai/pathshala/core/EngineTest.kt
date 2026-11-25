package ai.pathshala.core

import ai.pathshala.core.llm.OutputParsers
import ai.pathshala.core.llm.PackMatcher
import ai.pathshala.core.llm.Prompts
import ai.pathshala.core.llm.RuleGoalParser
import kotlin.test.*

class EngineTest {
    private val packs = PackRepository.loadAll()
    private val jee = packs.first { it.id == "jee_mechanics" }

    @Test fun packsAreValidDags() {
        assertEquals(3, packs.size)
        packs.forEach { p ->
            val g = SkillGraph(p)
            g.order.forEachIndexed { i, s -> s.prereqs.forEach { assertTrue(g.index.getValue(it) < i, "${s.id} before $it") } }
            assertTrue(p.allItems.size >= 2 * p.skills.size)
        }
    }

    @Test fun ancestorsAndDescendants() {
        val g = SkillGraph(jee)
        assertTrue("vectors" in g.ancestors("rotation"))
        assertTrue("rotation" in g.descendants("newton_laws"))
        assertEquals(0, g.layers()["vectors"])
    }

    @Test fun diagnosticRecoversADeterministicLearner() {
        // Learner who knows everything up to work_energy and nothing beyond.
        val g = SkillGraph(jee)
        val known = setOf("vectors", "kinematics_1d", "kinematics_2d", "newton_laws", "friction", "work_energy")
        val d = GraphDiagnostic(jee, SelfLevel.INTERMEDIATE, numParticles = 2000, seed = 3)
        repeat(12) {
            val item = d.nextItem() ?: return@repeat
            d.observe(item, item.skillId in known)
        }
        val m = d.marginals()
        val acc = g.order.count { (m.getValue(it.id) >= 0.5) == (it.id in known) }.toDouble() / g.n
        assertTrue(acc >= 0.8, "accuracy $acc, marginals $m")
        assertTrue(d.uncertainty() < d.maxUncertainty() * 0.75, "uncertainty ${d.uncertainty()} of ${d.maxUncertainty()}")
    }

    @Test fun graphPropagatesEvidenceToUnaskedSkills() {
        val d = GraphDiagnostic(jee, SelfLevel.INTERMEDIATE, numParticles = 3000, seed = 5)
        val before = d.marginals().getValue("rotation")
        jee.skill("newton_laws").items.forEach { d.observe(it, false) }
        // failing a foundation lowers belief in a distant dependent never asked about
        assertTrue(d.marginals().getValue("rotation") < before - 0.1, "rotation ${d.marginals().getValue("rotation")} vs $before")
    }

    @Test fun bktMovesInTheRightDirection() {
        val goal = LearnerGoal("x", jee.id)
        var st = LearnerModel.init(jee, goal, 0)
        val item = jee.skill("friction").items[0]
        val p0 = st.skills.getValue("friction").pMastery
        st = LearnerModel.recordAnswer(st, item, true, 0)
        assertTrue(st.skills.getValue("friction").pMastery > p0)
        val p1 = st.skills.getValue("friction").pMastery
        st = LearnerModel.recordAnswer(st, item, false, 1)
        assertTrue(st.skills.getValue("friction").pMastery < p1)
        assertEquals(1, st.skills.getValue("friction").recentWrong)
    }

    @Test fun fsrsForgettingAndSpacing() {
        val c = Fsrs.newCard(Rating.GOOD, 0)
        assertTrue(Fsrs.retrievability(c, 1) > Fsrs.retrievability(c, 10))
        val c2 = Fsrs.review(c, Rating.GOOD, 4)
        assertTrue(c2.stability > c.stability, "successful review grows stability")
        val c3 = Fsrs.review(c2, Rating.AGAIN, 30)
        assertTrue(c3.stability < c2.stability, "lapse shrinks stability")
        assertEquals(0.9, Fsrs.retrievability(Fsrs.interval(c2.stability), c2.stability), 1e-6)
    }

    @Test fun plannerRespectsPrerequisitesAndTriages() {
        val goal = LearnerGoal("x", jee.id, deadlineDays = 21, hoursPerWeek = 4.0)
        val st = LearnerModel.init(jee, goal, 0)
        val plan = Planner.plan(jee, st, 0)
        assertTrue(plan.triage, "4 h/week for 3 weeks cannot cover the pack")
        val scheduled = plan.path.filter { it.status == StepStatus.SCHEDULED }.map { it.skillId }
        scheduled.forEachIndexed { i, id ->
            jee.skill(id).prereqs.forEach { p ->
                val pi = scheduled.indexOf(p)
                assertTrue(pi == -1 || pi < i, "$p must precede $id")
                // closure: a scheduled skill never depends on a deferred one
                assertFalse(plan.path.any { it.skillId == p && it.status == StepStatus.DEFERRED })
            }
        }
        assertTrue(plan.projectedScore > plan.currentScore)
        assertTrue(plan.blocks.all { it.day >= 0 })
    }

    @Test fun plannerRemediatesRootCause() {
        val goal = LearnerGoal("x", jee.id, hoursPerWeek = 7.0)
        var st = LearnerModel.init(jee, goal, 0)
        st = st.copy(skills = st.skills + ("newton_laws" to SkillState(0.3)) + ("friction" to SkillState(0.4, recentWrong = 3)))
        val plan = Planner.plan(jee, st, 0)
        val first = plan.blocks.first()
        assertEquals(BlockKind.REMEDIATE, first.kind)
        assertEquals("newton_laws", first.skillId)
    }

    @Test fun reviewsAreScheduledForMasteredSkills() {
        val goal = LearnerGoal("x", jee.id, hoursPerWeek = 7.0)
        var st = LearnerModel.init(jee, goal, 0)
        st = st.copy(skills = st.skills + ("vectors" to SkillState(0.95, card = Fsrs.newCard(Rating.GOOD, 0))))
        val plan = Planner.plan(jee, st, 0)
        assertTrue(plan.blocks.any { it.kind == BlockKind.REVIEW && it.skillId == "vectors" })
    }

    @Test fun jsonExtractionSurvivesSmallModelSlips() {
        val messy = "Sure! Here is the JSON:\n{'pack': \"jee_mechanics\", \"deadline_days\": 90, \"hours_per_week\": 14, \"level\": \"Intermediate\", \"interests\": [\"cricket\",], }\nHope this helps"
        val obj = OutputParsers.extractJsonObject(messy)
        assertNotNull(obj)
        val rules = RuleGoalParser.parse("physics", packs, "jee_mechanics")
        val g = OutputParsers.mergeGoal("x", messy, packs, rules)
        assertEquals(90, g.deadlineDays); assertEquals(14.0, g.hoursPerWeek); assertEquals(SelfLevel.INTERMEDIATE, g.level)
        assertEquals(listOf("cricket"), g.interests)
        assertEquals("llama", g.provenance["deadline"])
    }

    @Test fun invalidLlamaFieldsFallBackToRules() {
        val rules = RuleGoalParser.parse("I want to learn pandas and machine learning, 1 hour daily", packs, "jee_mechanics")
        val g = OutputParsers.mergeGoal("x", "{\"pack\":\"astrology\",\"hours_per_week\":900}", packs, rules)
        assertEquals("python_ds", g.packId)
        assertEquals(7.0, g.hoursPerWeek)
        assertEquals("rules", g.provenance["hours"])
    }

    @Test fun ruleParserHandlesHinglishAndHindi() {
        val g = RuleGoalParser.parse("mujhe GATE chemical ki taiyari karni hai, 3 mahine me, roz 2 ghante", packs, "jee_mechanics")
        assertEquals("gate_cheme", g.packId); assertEquals(90, g.deadlineDays); assertEquals(14.0, g.hoursPerWeek); assertEquals("hi", g.language)
        val h = RuleGoalParser.parse("जेईई भौतिकी 2 महीने में, रोज़ 3 घंटे", packs, "python_ds")
        assertEquals("jee_mechanics", h.packId); assertEquals(60, h.deadlineDays); assertEquals(21.0, h.hoursPerWeek)
    }

    @Test fun packMatcherRoutesGoals() {
        assertEquals("python_ds", PackMatcher.bestPack("become a data analyst, learn SQL and pandas", packs)?.id)
        assertEquals("jee_mechanics", PackMatcher.bestPack("JEE mains rotation and kinematics", packs)?.id)
        assertEquals("gate_cheme", PackMatcher.bestPack("GATE CH heat transfer", packs)?.id)
        assertNull(PackMatcher.bestPack("learn to play the violin", packs))
        assertEquals("rotation", PackMatcher.bestSkill(jee, "rotation"))
    }

    @Test fun generatedPackParsingDropsBadLines() {
        val out = "Here you go\n1 | Music theory basics | 3 | -\n2 | Reading notation | 4 | 1\n3 | Bow technique | 5 | 1\n4 | Scales | 4 | 2,3\nrandom chatter\n5 | Vibrato | 3 | 9"
        val p = OutputParsers.parseGeneratedPack("learn violin", out)
        assertNotNull(p); PackRepository.validate(p)
        assertEquals(5, p.skills.size); assertFalse(p.verified)
        assertEquals(emptyList(), p.skill("g5").prereqs) // forward ref to 9 dropped
    }

    @Test fun teachBackParsing() {
        assertEquals(2.0 / 3, OutputParsers.teachBackScore("KP1: YES\nKP2: NO\nKP3: yes", 3)!!, 1e-9)
        assertNull(OutputParsers.teachBackScore("I think it is good", 3))
    }

    @Test fun promptsUseLlama3Template() {
        val p = Prompts.chat("sys", "hi")
        assertTrue(p.startsWith("<|start_header_id|>system"))
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        val plan = Planner.plan(jee, LearnerModel.init(jee, LearnerGoal("x", jee.id, deadlineDays = 30), 0), 0)
        val n = Prompts.narratePlan(jee, LearnerGoal("x", jee.id, deadlineDays = 30), plan, listOf("Learn: Vectors"))
        assertTrue(n.length < 3000, "narration prompt must stay small for fast prefill: ${n.length}")
    }
}
