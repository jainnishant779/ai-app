package com.nishu.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SttModelSpecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testModelSpecDefinitions() {
        assertEquals("whisper-small", SttModelSpec.WHISPER_SMALL_EN.dir)
        assertEquals("hinglish-swift", SttModelSpec.HINGLISH_SWIFT.dir)
        assertEquals("qwen3-hinglish", SttModelSpec.QWEN3_HINGLISH.dir)
        assertEquals(SttModelSpec.Engine.QWEN3_ASR, SttModelSpec.QWEN3_HINGLISH.engine)
        assertEquals(SttModelSpec.Engine.WHISPER, SttModelSpec.WHISPER_SMALL_EN.engine)
    }

    @Test
    fun testSelectFallbackWhenNothingInstalled() {
        val root = tmp.newFolder("empty_models")
        val selected = SttModelSpec.select(root, preferredLanguage = "hinglish", overrideModelId = "auto")
        assertEquals(SttModelSpec.TINY_EN, selected)
    }

    @Test
    fun testSelectInstalledSwift() {
        val root = tmp.newFolder("installed_models")
        val swiftDir = File(root, "hinglish-swift").apply { mkdirs() }
        File(swiftDir, "hinglish-swift-encoder.int8.onnx").writeText("dummy")
        File(swiftDir, "hinglish-swift-decoder.int8.onnx").writeText("dummy")
        File(swiftDir, "hinglish-swift-tokens.txt").writeText("dummy")

        assertTrue("Swift should be detected as installed", SttModelSpec.HINGLISH_SWIFT.isInstalled(root))

        val selected = SttModelSpec.select(root, preferredLanguage = "hinglish", overrideModelId = "auto")
        assertEquals(SttModelSpec.HINGLISH_SWIFT, selected)
    }

    @Test
    fun testSelectOverrideMatching() {
        val root = tmp.newFolder("installed_models2")
        val smallDir = File(root, "whisper-small").apply { mkdirs() }
        File(smallDir, "small-encoder.int8.onnx").writeText("dummy")
        File(smallDir, "small-decoder.int8.onnx").writeText("dummy")
        File(smallDir, "small-tokens.txt").writeText("dummy")

        assertTrue(SttModelSpec.WHISPER_SMALL_EN.isInstalled(root))

        // Match by fuzzy string "whisper-small"
        val selected = SttModelSpec.select(root, preferredLanguage = "hinglish", overrideModelId = "whisper-small")
        assertEquals(SttModelSpec.WHISPER_SMALL_EN, selected)
    }
}
