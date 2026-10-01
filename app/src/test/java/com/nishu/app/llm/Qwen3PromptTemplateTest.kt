package com.nishu.app.llm

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class Qwen3PromptTemplateTest {
    private val golden = File("src/sharedTest/golden")
    private val assetPrompt = File("src/main/assets/system_prompt.bin")

    private fun parse(json: String): List<ChatMessage> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val role = Role.valueOf(o.getString("role").uppercase())
            val tc = o.optJSONArray("tool_calls")?.getJSONObject(0)?.getJSONObject("function")?.let {
                ToolCall(it.getString("name"), it.get("arguments").let { a -> a.toString() })
            }
            ChatMessage(role, o.optString("content", ""), tc)
        }
    }

    private fun fixtures(): List<File> {
        val files = golden.listFiles { f -> f.name.endsWith(".messages.json") }?.sortedBy { it.name }.orEmpty()
        assertTrue("no golden fixtures; run tools/render_golden_prompts.py", files.isNotEmpty())
        return files
    }

    @Test
    fun systemPromptAssetMatchesFixtures() {
        assertTrue("system_prompt.bin missing; run tools/extract_system_prompt.py", assetPrompt.exists())
        val sha = MessageDigest.getInstance("SHA-256").digest(assetPrompt.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("0d30a62b8de680791d8acbc8a14e5d62df0d0cca2c4474a3ae63a98aead6f3c3", sha)
    }

    @Test
    fun rendersByteIdenticalToTransformers() {
        val template = Qwen3PromptTemplate(assetPrompt.readText(Charsets.UTF_8))
        for (f in fixtures()) {
            val all = parse(f.readText(Charsets.UTF_8))
            assertEquals(Role.SYSTEM, all.first().role)
            assertEquals(assetPrompt.readText(Charsets.UTF_8), all.first().content)
            val expected = File(golden, f.name.replace(".messages.json", ".prompt.bin"))
            assertTrue("${expected.name} missing; run tools/render_golden_prompts.py", expected.exists())
            val actual = template.render(all.drop(1))
            val want = expected.readBytes()
            if (!actual.contentEquals(want)) {
                val idx = actual.indices.firstOrNull { it >= want.size || actual[it] != want[it] } ?: actual.size
                fun ctx(b: ByteArray) = String(b, maxOf(0, idx - 20), minOf(40, b.size - maxOf(0, idx - 20)), Charsets.UTF_8)
                throw AssertionError("${f.name}: first diff at byte $idx\n  want: ${ctx(want)}\n  got:  ${ctx(actual)}")
            }
            assertArrayEquals(want, actual)
        }
    }

    @Test
    fun prefixPlusTurnsEqualsFullRender() {
        val template = Qwen3PromptTemplate(assetPrompt.readText(Charsets.UTF_8))
        val all = parse(fixtures().first().readText(Charsets.UTF_8)).drop(1)
        assertArrayEquals(template.renderPrefix() + template.renderTurns(all), template.render(all))
    }
}
