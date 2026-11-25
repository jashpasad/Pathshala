package ai.pathshala.app.llm

/** Runtime statistics reported by ExecuTorch's LLM runner (onStats JSON) or measured by us. */
data class GenStats(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val ttftMs: Long = 0,
    val prefillTokPerSec: Double = 0.0,
    val decodeTokPerSec: Double = 0.0,
    val totalMs: Long = 0,
    val rssMb: Long = 0,
    // hardware utilisation (DeviceProfile.Probe)
    val coresBusy: Double = 0.0,          // CPU time / wall time = average cores kept busy
    val peakThreads: Int = 0,
    val avgPowerW: Double? = null,        // battery current x voltage; null if the device doesn't report it
    val energyJ: Double? = null,
    val charging: Boolean = false,
) {
    val joulesPer100Tok: Double? get() = energyJ?.let { if (generatedTokens > 0) it * 100 / generatedTokens else null }
}

data class GenResult(val text: String, val stats: GenStats?, val engine: String)

/**
 * One interface, two implementations:
 *  - [ExecuTorchEngine]: Llama 3.2 (1B/3B, 4-bit) on the Arm CPU via ExecuTorch + XNNPACK/KleidiAI.
 *  - [TemplateEngine]:   deterministic fallback so the learning engine works with no model present
 *                        (emulator, first launch before the model is copied). Clearly labelled in UI.
 */
interface LlmEngine {
    val name: String
    val isLlama: Boolean
    /**
     * @param continueSession append to the existing KV cache (multi-turn tutor) instead of resetting.
     * @param temperature 0 = greedy (parsing/grading), ~0.6 for natural language.
     */
    suspend fun generate(
        prompt: String,
        maxNewTokens: Int,
        temperature: Float = 0.6f,
        continueSession: Boolean = false,
        onToken: (String) -> Unit = {},
    ): GenResult

    fun stop() {}
}

/** Strip Llama special tokens and trailing whitespace from streamed output. */
fun cleanLlamaText(s: String): String =
    s.replace(Regex("<\\|[a-z_]+\\|>"), "").replace("assistant\n\n", "").trim()
