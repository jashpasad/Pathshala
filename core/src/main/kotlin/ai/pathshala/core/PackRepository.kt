package ai.pathshala.core

import kotlinx.serialization.json.Json

/** Loads curated packs bundled as Java resources (shared by the Android app, tests and simulator). */
object PackRepository {
    val PACK_FILES = listOf("jee_mechanics.json", "python_ds.json", "gate_cheme.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): SkillPack = json.decodeFromString(SkillPack.serializer(), text).also { validate(it) }

    fun loadAll(): List<SkillPack> = PACK_FILES.map { name ->
        val stream = PackRepository::class.java.getResourceAsStream("/packs/$name") ?: error("Missing pack $name")
        parse(stream.bufferedReader(Charsets.UTF_8).readText())
    }

    /** Structural validation: DAG, item -> skill references, answer indices, weights. */
    fun validate(p: SkillPack) {
        SkillGraph(p) // throws on unknown prereq or cycle
        val ids = p.skills.map { it.id }
        require(ids.size == ids.toSet().size) { "Duplicate skill ids in ${p.id}" }
        p.skills.forEach { s ->
            require(s.hours > 0 && s.weight > 0) { "Bad hours/weight in ${s.id}" }
            s.items.forEach { i ->
                require(i.skillId == s.id) { "Item ${i.id} under ${s.id} claims ${i.skillId}" }
                require(i.answer in i.options.indices) { "Item ${i.id} answer out of range" }
                require(i.options.size >= 2) { "Item ${i.id} needs options" }
            }
        }
        val itemIds = p.allItems.map { it.id }
        require(itemIds.size == itemIds.toSet().size) { "Duplicate item ids in ${p.id}" }
    }
}
