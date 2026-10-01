package com.nishu.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun dimensity7400HasFourBigCores() {
        // Motorola Edge 60 Fusion (MT6878): 4x A78 @2.6 GHz + 4x A55 @2.0 GHz.
        val freqs = List(4) { 2_600_000L } + List(4) { 2_000_000L }
        assertEquals(4, DeviceProfile.bigCoreCount(freqs))
    }

    @Test
    fun snapdragon8Gen3CountsPrimeAndPerformanceCores() {
        val freqs = listOf(3_300_000L, 3_150_000, 3_150_000, 3_150_000, 2_960_000, 2_960_000, 2_960_000, 2_270_000)
        val p = DeviceProfile("Qualcomm SM8650", 8, DeviceProfile.bigCoreCount(freqs))
        assertEquals(7, p.bigCores)
        assertEquals("decode capped: more threads contend for memory bandwidth", 4, p.decodeThreads)
        assertEquals(6, p.batchThreads)
    }

    @Test
    fun threadCountsAreAlwaysUsable() {
        assertEquals(2, DeviceProfile("x", 2, 1).decodeThreads)
        assertEquals(4, DeviceProfile.bigCoreCount(emptyList()))
        assertEquals(1, DeviceProfile.bigCoreCount(listOf(1_000_000L)))
    }
}
