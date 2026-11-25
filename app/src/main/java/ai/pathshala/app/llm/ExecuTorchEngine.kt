package ai.pathshala.app.llm

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Llama 3.2 Instruct on-device via ExecuTorch (verified against executorch-android 1.5.1 API).
 *
 * Systems choices (see report section 6):
 *  - All native calls on ONE dedicated thread: LlmModule is not thread-safe and generate() blocks.
 *  - mmap load mode: weights are paged from flash on demand -> lower peak RSS on 6-8 GB phones.
 *  - KV-cache session continuity for the tutor: the system prompt + concept card are prefilled once
 *    per session; each new turn only prefills the user's message (numBos = 0, no resetContext).
 *    A token ledger triggers a reset + re-prefill before the exported context limit is reached.
 *  - Thermal-aware budgets: max_new_tokens shrinks when the SoC reports MODERATE or worse.
 */
class ExecuTorchEngine(
    private val modelFile: File,
    private val tokenizerFile: File,
    private val device: DeviceProfile,
    private val maxContext: Int = 2048,
) : LlmEngine {
    override val name = "Llama (ExecuTorch) · ${modelFile.name}"
    override val isLlama = true

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-runner").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = thread.asCoroutineDispatcher()
    private var module: LlmModule? = null
    private var sessionTokens = 0
    var loadMs: Long = 0; private set

    suspend fun load(): Result<Long> = withContext(dispatcher) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val cfg = LlmModuleConfig.create()
                .modulePath(modelFile.absolutePath)
                .tokenizerPath(tokenizerFile.absolutePath)
                .temperature(0.6f)
                .modelType(LlmModule.MODEL_TYPE_TEXT)
                .loadMode(LlmModuleConfig.LOAD_MODE_MMAP)
                .build()
            val m = LlmModule(cfg)
            m.load()
            module = m
            loadMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Loaded ${modelFile.name} in $loadMs ms")
            loadMs
        }
    }

    override suspend fun generate(
        prompt: String,
        maxNewTokens: Int,
        temperature: Float,
        continueSession: Boolean,
        onToken: (String) -> Unit,
    ): GenResult = withContext(dispatcher) {
        val m = module ?: error("Model not loaded")
        val budget = device.thermalBudget(maxNewTokens)
        val approxPromptTokens = prompt.length / 3            // conservative chars->tokens estimate
        val continuing = continueSession && sessionTokens > 0 && sessionTokens + approxPromptTokens + budget < maxContext - 64
        if (!continuing) { m.resetContext(); sessionTokens = 0 }

        val cfg = LlmGenerationConfig.create()
            .maxNewTokens(budget)
            .temperature(temperature)
            .echo(false)
            .numBos(if (continuing) 0 else 1)
            .numEos(0)
            .build()

        val sb = StringBuilder()
        val statsDone = CompletableDeferred<String?>()
        val t0 = System.currentTimeMillis()
        val probe = device.probe()
        var firstTokenAt = 0L
        var errorMsg: String? = null
        m.generate(prompt, cfg, object : LlmCallback {
            override fun onResult(result: String) {
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                sb.append(result)
                val clean = result.replace(Regex("<\\|[a-z_]+\\|>"), "")
                if (clean.isNotEmpty()) onToken(clean)
                if (result.contains("<|eot_id|>")) m.stop()
            }
            override fun onStats(stats: String) { statsDone.complete(stats) }
            override fun onError(errorCode: Int, message: String) { errorMsg = "ExecuTorch error $errorCode: $message"; statsDone.complete(null) }
        })
        if (!statsDone.isCompleted) statsDone.complete(null)
        errorMsg?.let { Log.e(TAG, it) }
        val hw = probe.finish()
        val stats = parseStats(statsDone.await(), t0, firstTokenAt, sb.length)?.copy(
            coresBusy = hw.coresBusy, peakThreads = hw.peakThreads, rssMb = hw.peakRssMb,
            avgPowerW = hw.avgPowerW, energyJ = hw.energyJ, charging = hw.charging,
        )
        sessionTokens += (stats?.promptTokens ?: approxPromptTokens) + (stats?.generatedTokens ?: (sb.length / 3))
        GenResult(cleanLlamaText(sb.toString()), stats, name)
    }

    override fun stop() { module?.stop() }

    private fun parseStats(json: String?, t0: Long, firstTokenAt: Long, chars: Int): GenStats? {
        val rss = DeviceProfile.rssMb()
        if (json == null) {
            val total = System.currentTimeMillis() - t0
            return GenStats(ttftMs = if (firstTokenAt > 0) firstTokenAt - t0 else 0, totalMs = total, rssMb = rss,
                generatedTokens = chars / 4)
        }
        return runCatching {
            val o = JSONObject(json)
            val p = o.optInt("prompt_tokens"); val g = o.optInt("generated_tokens")
            val inf0 = o.optLong("inference_start_ms"); val inf1 = o.optLong("inference_end_ms")
            val pe = o.optLong("prompt_eval_end_ms"); val ft = o.optLong("first_token_ms")
            GenStats(
                promptTokens = p, generatedTokens = g,
                ttftMs = (ft - inf0).coerceAtLeast(0),
                prefillTokPerSec = if (pe > inf0) p * 1000.0 / (pe - inf0) else 0.0,
                decodeTokPerSec = if (inf1 > pe && g > 1) (g - 1) * 1000.0 / (inf1 - pe) else 0.0,
                totalMs = (inf1 - inf0).coerceAtLeast(0), rssMb = rss,
            )
        }.getOrNull()
    }

    fun close() { thread.execute { module?.close(); module = null } }

    companion object {
        private const val TAG = "PathShala/LLM"

        /** Look for model + tokenizer in app-specific storage first (adb push needs no permission there). */
        fun discover(dirs: List<File>): Pair<File, File>? {
            for (dir in dirs) {
                val files = dir.listFiles() ?: continue
                val pte = files.filter { it.name.endsWith(".pte") }.maxByOrNull { if (it.name.contains("1B", true)) 1 else 0 } ?: continue
                val tok = files.firstOrNull { it.name in listOf("tokenizer.model", "tokenizer.bin", "tokenizer.json") || it.name.endsWith(".model") }
                    ?: continue
                return pte to tok
            }
            return null
        }
    }
}
