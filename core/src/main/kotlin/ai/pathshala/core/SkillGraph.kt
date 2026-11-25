package ai.pathshala.core

/** Prerequisite DAG utilities over a [SkillPack]. Indexes skills 0..n-1 in topological order. */
class SkillGraph(val pack: SkillPack) {
    val n: Int = pack.skills.size
    /** Skills in a valid topological order (prerequisites first). */
    val order: List<Skill>
    val index: Map<String, Int>
    val parents: Array<IntArray>
    val children: Array<IntArray>

    init {
        val byId = pack.skills.associateBy { it.id }
        pack.skills.forEach { s ->
            s.prereqs.forEach { p -> require(p in byId) { "Skill ${s.id} has unknown prereq $p" } }
        }
        // Kahn's algorithm; stable w.r.t. authoring order.
        val indeg = pack.skills.associate { it.id to it.prereqs.size }.toMutableMap()
        val out = mutableListOf<Skill>()
        val ready = ArrayDeque(pack.skills.filter { indeg[it.id] == 0 })
        val childrenOf = pack.skills.associate { s -> s.id to pack.skills.filter { s.id in it.prereqs } }
        while (ready.isNotEmpty()) {
            val s = ready.removeFirst()
            out += s
            for (c in childrenOf.getValue(s.id)) {
                indeg[c.id] = indeg.getValue(c.id) - 1
                if (indeg[c.id] == 0) ready.addLast(c)
            }
        }
        require(out.size == pack.skills.size) { "Pack ${pack.id} has a prerequisite cycle" }
        order = out
        index = order.withIndex().associate { (i, s) -> s.id to i }
        parents = Array(n) { i -> order[i].prereqs.map { index.getValue(it) }.toIntArray() }
        children = Array(n) { i -> (0 until n).filter { i in parents[it] }.toIntArray() }
    }

    fun ancestors(id: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque(pack.skill(id).prereqs)
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            if (seen.add(p)) stack.addAll(pack.skill(p).prereqs)
        }
        return seen
    }

    fun descendants(id: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque(children[index.getValue(id)].map { order[it].id })
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (seen.add(c)) stack.addAll(children[index.getValue(c)].map { order[it].id })
        }
        return seen
    }

    /** Longest-path layer of each skill (0 = no prerequisites); used for the skill-map layout. */
    fun layers(): Map<String, Int> {
        val layer = IntArray(n)
        for (i in 0 until n) layer[i] = (parents[i].maxOfOrNull { layer[it] + 1 } ?: 0)
        return order.indices.associate { order[it].id to layer[it] }
    }
}
