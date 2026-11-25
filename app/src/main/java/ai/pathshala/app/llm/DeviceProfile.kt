package ai.pathshala.app.llm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Arm-aware device profiling: SoC, RAM, CPU ISA features that decide which KleidiAI micro-kernels
 * XNNPACK can dispatch (dotprod -> SDOT int8, i8mm -> SMMLA int8 matmul, SME2 on newest cores),
 * and thermal state for adaptive generation budgets.
 */
class DeviceProfile(private val ctx: Context) {
    val totalRamGb: Double = run {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem / 1e9
    }
    val soc: String = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    val cpuFeatures: Set<String> = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
            ?.substringAfter(":")?.trim()?.split(" ")?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())
    val cores: Int = Runtime.getRuntime().availableProcessors()

    val hasDotProd get() = "asimddp" in cpuFeatures
    val hasI8mm get() = "i8mm" in cpuFeatures
    val hasSve get() = "sve" in cpuFeatures || "sve2" in cpuFeatures
    val hasSme get() = cpuFeatures.any { it.startsWith("sme") }

    /** 1B for <10 GB RAM (fits alongside apps on 6-8 GB budget phones); 3B is opt-in on 12 GB+. */
    val recommendedModel: String get() = if (totalRamGb >= 11.0) "Llama 3.2 3B SpinQuant (optional) or 1B" else "Llama 3.2 1B SpinQuant INT4"

    fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29)
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0

    fun thermalLabel(): String = when (thermalStatus()) {
        0 -> "None"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; else -> "Shutdown"
    }

    /** Shrink generation length under thermal pressure: predictable latency, cooler phone, longer battery. */
    fun thermalBudget(maxNewTokens: Int): Int = when (thermalStatus()) {
        0, 1 -> maxNewTokens
        2 -> (maxNewTokens * 0.6).toInt()
        else -> (maxNewTokens * 0.35).toInt().coerceAtLeast(24)
    }

    fun isaSummary(): String = buildString {
        append(abi)
        append(" · dotprod ").append(if (hasDotProd) "✓" else "✗")
        append(" · i8mm ").append(if (hasI8mm) "✓" else "✗")
        append(" · SVE ").append(if (hasSve) "✓" else "✗")
        append(" · SME ").append(if (hasSme) "✓" else "✗")
    }

    // ------------------------------------------------------------------ hardware utilisation probe
    /** What one LLM call cost the hardware: CPU time across all cores, threads, RSS peak, battery power. */
    data class HwSample(
        val wallMs: Long, val cpuMs: Long, val peakThreads: Int, val peakRssMb: Long,
        val avgPowerW: Double?, val charging: Boolean,
    ) {
        /** Average number of cores kept busy (CPU time / wall time). */
        val coresBusy: Double get() = if (wallMs > 0) cpuMs.toDouble() / wallMs else 0.0
        val energyJ: Double? get() = avgPowerW?.let { it * wallMs / 1000.0 }
    }

    inner class Probe internal constructor() {
        private val cpu0 = cpuTimeMs(); private val t0 = System.currentTimeMillis()
        private val powerSamples = mutableListOf<Double>()
        private var peakThreads = threadCount(); private var peakRss = rssMb()
        private val task: ScheduledFuture<*> = sampler.scheduleAtFixedRate({
            powerW()?.let { synchronized(powerSamples) { powerSamples += it } }
            peakThreads = maxOf(peakThreads, threadCount()); peakRss = maxOf(peakRss, rssMb())
        }, 0, 250, TimeUnit.MILLISECONDS)

        fun finish(): HwSample {
            task.cancel(false)
            val p = synchronized(powerSamples) { powerSamples.toList() }
            return HwSample(System.currentTimeMillis() - t0, cpuTimeMs() - cpu0, peakThreads, peakRss,
                p.takeIf { it.size >= 2 }?.average(), isCharging())
        }
    }

    fun probe() = Probe()

    private val sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hw-probe").apply { isDaemon = true } }
    private val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /** Instantaneous battery power in watts from BatteryManager current x voltage (null if unsupported). */
    fun powerW(): Double? = runCatching {
        val microAmps = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (microAmps == 0 || microAmps == Int.MIN_VALUE) return null
        val mv = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (mv <= 0) return null
        // Some OEMs report mA instead of µA: normalise anything implausibly small
        val amps = abs(microAmps).let { if (it < 20_000) it / 1000.0 else it / 1_000_000.0 }
        amps * mv / 1000.0
    }.getOrNull()

    fun isCharging(): Boolean = runCatching { battery.isCharging }.getOrDefault(false)

    companion object {
        private val clkTck: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1) }

        /** Process CPU time (user + system, all threads) in ms from /proc/self/stat. */
        fun cpuTimeMs(): Long = runCatching {
            val f = File("/proc/self/stat").readText().substringAfterLast(")").trim().split(" ")
            // after "pid (comm)" the fields start at index 3 (state); utime = field 14, stime = field 15
            (f[11].toLong() + f[12].toLong()) * 1000 / clkTck
        }.getOrDefault(0)

        fun threadCount(): Int = File("/proc/self/task").list()?.size ?: 0

        fun rssMb(): Long = runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }
                .filter { it.isDigit() }.toLong() / 1024
        }.getOrDefault(0)
    }
}
