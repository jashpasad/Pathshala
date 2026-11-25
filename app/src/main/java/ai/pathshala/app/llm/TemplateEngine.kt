package ai.pathshala.app.llm

import kotlinx.coroutines.delay

/**
 * Deterministic fallback used when no Llama model file is present. It never pretends to be the LLM:
 * the UI labels every output "template mode". It lets the symbolic engine (diagnosis, planning,
 * spaced review) be demonstrated and tested anywhere, and keeps the app useful during the
 * first-run model copy.
 */
class TemplateEngine : LlmEngine {
    override val name = "Template mode (no Llama model loaded)"
    override val isLlama = false

    var facts: List<String> = emptyList()

    override suspend fun generate(
        prompt: String, maxNewTokens: Int, temperature: Float, continueSession: Boolean, onToken: (String) -> Unit,
    ): GenResult {
        val text = when {
            prompt.contains("convert a learner's message into JSON") -> ""          // rules parser takes over
            prompt.contains("KP1:") -> gradeByKeywords(prompt)
            prompt.contains("Explain the study plan") -> narrate(prompt)
            prompt.contains("giving a hint") -> facts.firstOrNull()?.let { "Hint: think about this idea — $it" } ?: "Re-read the key idea for this topic."
            prompt.contains("Socratic tutor") || prompt.contains("<|start_header_id|>user") ->
                "Template mode can't hold a conversation. Load the Llama model on the Engine tab. Meanwhile, the key ideas:\n" +
                    facts.take(3).joinToString("\n") { "• $it" } + "\nWhich of these feels least clear?"
            else -> ""
        }
        // stream word by word so the UI behaves identically in both modes
        for (w in text.split(" ")) { onToken("$w "); delay(8) }
        return GenResult(text, null, name)
    }

    private fun narrate(prompt: String): String {
        val body = prompt.substringAfter("<|start_header_id|>user<|end_header_id|>\n\n").substringBefore("<|eot_id|>")
        val lines = body.lines().filter { it.isNotBlank() }
        val first = lines.firstOrNull { it.startsWith("- ") }?.removePrefix("- ")
        return buildString {
            lines.firstOrNull()?.let { append(it).append(' ') }
            lines.firstOrNull { it.startsWith("Readiness") }?.let { append(it).append(' ') }
            first?.let { append("Start with ${it.substringBefore(":")} because ${it.substringAfter(": ").lowercase()}. ") }
            if (body.contains("Deferred")) append("Time is tight, so some lower-value topics are deferred. ")
            append("Follow today's blocks and the plan will update after every practice session.")
        }
    }

    private fun gradeByKeywords(prompt: String): String {
        val explanation = prompt.substringAfter("Student explanation:").lowercase()
        val kps = Regex("KP(\\d+): (.+)").findAll(prompt).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return kps.joinToString("\n") { (i, kp) ->
            val words = kp.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }.toSet()
            val hit = words.count { it in explanation } >= maxOf(1, words.size / 4)
            "KP$i: ${if (hit) "YES" else "NO"}"
        }
    }
}
