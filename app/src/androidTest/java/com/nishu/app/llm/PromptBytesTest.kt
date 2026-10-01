package com.nishu.app.llm

import com.nishu.app.llm.llamacpp.LlamaBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contract test: prompt bytes AND token ids must equal what transformers renders. */
class PromptBytesTest {
    private fun handleFor(engine: Any): Long {
        val f = engine.javaClass.getDeclaredField("handle")
        f.isAccessible = true
        return f.getLong(engine)
    }

    @Test
    fun promptBytesAndTokenIdsMatchTransformers() {
        val engine = EngineTestSupport.loadEngine()
        engine.use {
            val handle = handleFor(it)
            val names = EngineTestSupport.fixtureNames()
            assertTrue("no fixtures in test assets", names.isNotEmpty())
            for (name in names) {
                val base = name.removeSuffix(".messages.json")
                val messages = EngineTestSupport.fixtureMessages(name)
                val rendered = it.template.render(messages.drop(1))

                assertArrayEquals("$base: prompt bytes", EngineTestSupport.fixtureBytes("$base.prompt.bin"), rendered)

                val want = EngineTestSupport.fixtureTokens("$base.tokens.json")
                val got = LlamaBridge.tokenize(handle, rendered)
                assertArrayEquals("$base: token ids (addSpecial must be false)", want, got)

                val split = LlamaBridge.tokenize(handle, it.template.renderPrefix()) +
                    LlamaBridge.tokenize(handle, it.template.renderTurns(messages.drop(1)))
                assertArrayEquals("$base: prefix+turns tokenization", want, split)
            }
            EngineTestSupport.log("systemPrefixTokenCount=${it.systemPrefixTokens} fixtures=${names.size}")
            assertTrue(it.systemPrefixTokens in 200..400)
            assertEquals(1024, it.contextTokens)
        }
    }
}
