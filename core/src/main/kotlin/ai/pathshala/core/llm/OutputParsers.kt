package ai.pathshala.core.llm

import ai.pathshala.core.*
import kotlinx.serialization.json.*
import kotlin.math.ln

/**
 * "Draft -> validate -> repair -> fall back": every Llama output passes through a deterministic
 * parser. A field Llama gets wrong is replaced by the rule-based value and the provenance is shown
 * to the learner, so a bad generation can degrade quality but never break the app.
 */
object OutputParsers {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Extract the first balanced {...} object (string-aware), fix common 1B-model JSON slips. */
    fun extractJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false; var end = -1
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
        }
        var raw = if (end > 0) text.substring(start, end + 1) else text.substring(start) + "}"
        raw = raw.replace(Regex(",\\s*([}\\]])"), "$1")          // trailing commas
            .replace(Regex("(?<=[{,]\\s{0,3})'([A-Za-z_]+)'\\s*:"), "\"$1\":") // 'key':
            .replace(": None", ": null").replace(":None", ":null")
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    /** Merge Llama's JSON with the rule-based parse; Llama wins only where its value validates. */
    fun mergeGoal(rawText: String, llmOut: String?, packs: List<SkillPack>, rules: LearnerGoal): LearnerGoal {
        val obj = llmOut?.let { extractJsonObject(it) } ?: return rules.copy(provenance = rules.provenance)
        val prov = rules.provenance.toMutableMap()
        var g = rules
        obj["pack"]?.jsonPrimitive?.contentOrNull?.let { id ->
            if (packs.any { it.id == id }) { g = g.copy(packId = id); prov["pack"] = "llama" }
        }
        obj["deadline_days"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..1500 }?.let {
            g = g.copy(deadlineDays = it); prov["deadline"] = "llama"
        }
        obj["hours_per_week"]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 0.5..80.0 }?.let {
            g = g.copy(hoursPerWeek = it); prov["hours"] = "llama"
        }
        obj["level"]?.jsonPrimitive?.contentOrNull?.let { lv ->
            SelfLevel.entries.firstOrNull { it.name.equals(lv.trim(), true) }?.let { g = g.copy(level = it); prov["level"] = "llama" }
        }
        (obj["interests"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.length in 2..30 } }
            ?.takeIf { it.isNotEmpty() }?.let { g = g.copy(interests = it.take(4)); prov["interests"] = "llama" }
        (obj["focus"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.let { focus ->
            val pack = packs.firstOrNull { it.id == g.packId }
            val ids = pack?.let { p -> focus.mapNotNull { f -> PackMatcher.bestSkill(p, f) } }.orEmpty()
            if (ids.isNotEmpty()) { g = g.copy(focusSkillIds = ids.distinct()); prov["focus"] = "llama" }
        }
        return g.copy(rawText = rawText, provenance = prov)
    }

    /** Parse "KP1: YES" lines into a coverage score in [0,1]; null if unparseable. */
    fun teachBackScore(text: String, nKeyPoints: Int): Double? {
        val hits = Regex("KP\\s*(\\d+)\\s*[:\\-]\\s*(YES|NO|हाँ|नहीं)", RegexOption.IGNORE_CASE).findAll(text)
            .associate { it.groupValues[1].toInt() to (it.groupValues[2].uppercase() == "YES" || it.groupValues[2] == "हाँ") }
            .filterKeys { it in 1..nKeyPoints }
        if (hits.size < (nKeyPoints + 1) / 2) return null
        return hits.values.count { it }.toDouble() / nKeyPoints
    }

    /** Parse the line-format roadmap into an (unverified) pack. Cycles/forward refs are dropped. */
    fun parseGeneratedPack(goalText: String, text: String): SkillPack? {
        val rows = text.lines().mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 3) return@mapNotNull null
            val num = parts[0].trimStart('-', '*', ' ').toIntOrNull() ?: return@mapNotNull null
            val name = parts[1].takeIf { it.length in 2..60 } ?: return@mapNotNull null
            val hours = Regex("[0-9]+(\\.[0-9]+)?").find(parts[2])?.value?.toDoubleOrNull()?.coerceIn(0.5, 40.0) ?: 3.0
            val pre = parts.getOrNull(3).orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it < num }
            Triple(num, name, hours) to pre
        }.distinctBy { it.first.first }.take(14)
        if (rows.size < 3) return null
        val nums = rows.map { it.first.first }.toSet()
        val skills = rows.map { (t, pre) ->
            Skill(id = "g${t.first}", name = t.second, summary = t.second, hours = t.third,
                prereqs = pre.filter { it in nums }.map { "g$it" })
        }
        return SkillPack(
            id = "gen_" + goalText.lowercase().filter { it.isLetterOrDigit() }.take(24),
            title = goalText.take(60), domain = "skill", description = "Roadmap drafted on-device by Llama. Unverified.",
            skills = skills, verified = false, source = "Llama 3.2 on-device draft",
        )
    }
}

/** BM25 retrieval over pack and skill text; maps free-text goals/topics to pack and skill ids offline. */
object PackMatcher {
    private val stop = setOf("to", "the", "a", "an", "and", "of", "in", "for", "on", "with", "my", "me", "is", "be", "it",
        "want", "learn", "study", "become", "get", "how", "from", "can", "will", "need", "hai", "ki", "ka", "ke", "me", "mujhe",
        "karna", "karni", "taiyari", "padhai", "seekhna", "hours", "hour", "daily", "week", "weeks", "months", "month", "days", "roz", "ghante")
    private fun tok(s: String) = s.lowercase().split(Regex("[^a-z0-9\\u0900-\\u097F+#]+")).filter { it.length > 1 && it !in stop }.map { if (it.length > 6) it.take(6) else it } // crude stemming

    fun rank(query: String, packs: List<SkillPack>): List<Pair<SkillPack, Double>> {
        val docs = packs.map { p -> p to tok(p.title + " " + p.titleHi + " " + p.description + " " + p.keywords.joinToString(" ") + " " + p.skills.joinToString(" ") { it.name + " " + it.nameHi }) }
        val q = tok(query).toSet()
        val avgLen = docs.map { it.second.size }.average()
        val df = q.associateWith { t -> docs.count { t in it.second } }
        return docs.map { (p, d) ->
            val tf = d.groupingBy { it }.eachCount()
            val score = q.sumOf { t ->
                val f = tf[t] ?: 0
                if (f == 0) 0.0 else {
                    val idf = ln(1 + (docs.size - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                    idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * d.size / avgLen))
                }
            }
            p to score
        }.sortedByDescending { it.second }
    }

    fun bestPack(query: String, packs: List<SkillPack>, minScore: Double = 0.8): SkillPack? =
        rank(query, packs).firstOrNull()?.takeIf { it.second >= minScore }?.first

    fun bestSkill(pack: SkillPack, query: String): String? {
        val q = tok(query).toSet()
        return pack.skills.maxByOrNull { s -> tok(s.name + " " + s.nameHi + " " + s.summary).count { it in q } }
            ?.takeIf { s -> tok(s.name + " " + s.nameHi + " " + s.summary).any { it in q } }?.id
    }
}

/** Deterministic goal parser (English, Hindi, Hinglish). Always runs; Llama refines on top. */
object RuleGoalParser {
    private val numWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5)

    private fun num(s: String): Double? = s.toDoubleOrNull() ?: numWords[s.lowercase()]?.toDouble()

    fun parse(text: String, packs: List<SkillPack>, fallbackPackId: String): LearnerGoal {
        val t = text.lowercase()
        val prov = mutableMapOf<String, String>()
        val pack = PackMatcher.bestPack(text, packs)
        if (pack != null) prov["pack"] = "rules"

        val n = "([0-9]+(?:\\.[0-9]+)?|one|two|three|four|five|six|ek|do|teen|char|paanch|एक|दो|तीन|चार|पांच)"
        var hours: Double? = null
        Regex("$n\\s*(?:hours?|hrs?|h|ghante|घंटे|घंटा)\\s*(?:a|per|every|each|har|roz|daily|/)?\\s*(day|daily|week|weekly|din|roz|दिन|रोज़|हफ्ते|week)?").find(t)?.let { m ->
            val v = num(m.groupValues[1])
            val unit = m.groupValues[2]
            if (v != null) hours = if (unit.isBlank() || unit in listOf("day", "daily", "din", "roz", "दिन", "रोज़")) v * 7 else v
            if (unit.isBlank() && Regex("week|हफ्ते").containsMatchIn(t.substring(m.range.last.coerceAtMost(t.length - 1)))) hours = v
        }
        if (hours != null) prov["hours"] = "rules"

        var days: Int? = null
        Regex("$n\\s*(days?|weeks?|months?|din|hafte|mahine|दिन|हफ्ते|महीने|महीना)").find(t)?.let { m ->
            val v = num(m.groupValues[1]) ?: return@let
            days = when {
                m.groupValues[2].startsWith("week") || m.groupValues[2] in listOf("hafte", "हफ्ते") -> (v * 7).toInt()
                m.groupValues[2].startsWith("month") || m.groupValues[2] in listOf("mahine", "महीने", "महीना") -> (v * 30).toInt()
                else -> v.toInt()
            }
        }
        if (days != null) prov["deadline"] = "rules"

        val level = when {
            Regex("beginner|basics?|from scratch|never|zero|शुरू|बिल्कुल नया|weak|kamzor").containsMatchIn(t) -> SelfLevel.BEGINNER
            Regex("advanced|expert|revise|revision|strong|already know").containsMatchIn(t) -> SelfLevel.ADVANCED
            Regex("intermediate|some|little|thoda|थोड़ा|average").containsMatchIn(t) -> SelfLevel.INTERMEDIATE
            else -> null
        }
        if (level != null) prov["level"] = "rules"

        val interests = Regex("(?:i (?:love|like|enjoy)|interested in|fan of|pasand hai|शौक)\\s+([a-z\\u0900-\\u097F ]{3,25})").findAll(t)
            .map { it.groupValues[1].trim().split(" ").take(2).joinToString(" ") }.toList()
        if (interests.isNotEmpty()) prov["interests"] = "rules"

        val hindi = text.any { it in 'ऀ'..'ॿ' } || Regex("\\b(mujhe|hai|karna|padhai|seekhna|chahta|chahti)\\b").containsMatchIn(t)
        return LearnerGoal(
            rawText = text, packId = pack?.id ?: fallbackPackId, deadlineDays = days, hoursPerWeek = hours ?: 6.0,
            level = level ?: SelfLevel.BEGINNER, interests = interests.take(4), language = if (hindi) "hi" else "en",
            provenance = prov,
        )
    }

    fun missingFields(g: LearnerGoal): List<String> = buildList {
        if ("pack" !in g.provenance) add("what you want to learn")
        if ("hours" !in g.provenance) add("hours per week")
        if ("deadline" !in g.provenance && (g.packId.contains("jee") || g.packId.contains("gate"))) add("exam date")
    }
}
