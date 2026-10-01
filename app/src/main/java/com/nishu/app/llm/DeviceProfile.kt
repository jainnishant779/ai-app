package com.nishu.app.llm

import android.os.Build
import java.io.File

/**
 * What this phone is, in the terms that matter for running the model: how many fast cores it has, and which SoC.
 * Little cores slow a synchronized matmul down, so threads are counted from the fast cores only.
 */
data class DeviceProfile(val soc: String, val totalCores: Int, val bigCores: Int) {
    /** Token generation is memory-bound: more than 4 threads stops helping and starts contending. */
    val decodeThreads: Int get() = bigCores.coerceIn(2, 4)

    /** Prompt processing is compute-bound and scales a little further. */
    val batchThreads: Int get() = bigCores.coerceIn(2, 6)

    companion object {
        /** Cores within 20% of the fastest core's frequency count as "big". */
        fun bigCoreCount(maxFreqsKhz: List<Long>): Int {
            if (maxFreqsKhz.isEmpty()) return 4
            val top = maxFreqsKhz.max()
            return maxFreqsKhz.count { it >= top * 0.8 }.coerceAtLeast(1)
        }

        fun read(): DeviceProfile {
            val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
            val soc = if (Build.VERSION.SDK_INT >= 31) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
            } else {
                ""
            }.ifBlank { Build.HARDWARE }
            return DeviceProfile(soc, freqs.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors(), bigCoreCount(freqs))
        }
    }
}
