package com.nishu.app.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolCallParserTest {
    private val parser = ToolCallParser()

    @Test
    fun parsesEveryToolCallInTrainingData() {
        val file = File("src/sharedTest/golden/tool_calls_all.jsonl")
        assertTrue("run tools/render_golden_prompts.py", file.exists())
        var n = 0
        file.useLines(Charsets.UTF_8) { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val row = JSONObject(line)
                val parsed = parser.parse(row.getString("rendered"))
                assertNotNull("failed to parse: $line", parsed)
                assertEquals(row.getString("name"), parsed!!.name)
                assertTrue("arguments differ: $line", row.getJSONObject("arguments").similar(parsed.arguments))
                assertTrue(!parsed.anomaly)
                n++
            }
        }
        assertEquals(1462, n)
    }

    @Test
    fun takesFirstOfMultipleCallsAndFlagsAnomaly() {
        val text = "<tool_call>\n{\"name\": \"a\", \"arguments\": {\"x\": 1}}\n</tool_call>\n" +
            "<tool_call>\n{\"name\": \"b\", \"arguments\": {}}\n</tool_call>"
        val p = parser.parse(text)!!
        assertEquals("a", p.name)
        assertTrue(p.anomaly)
    }

    @Test
    fun acceptsArgumentsAsString() {
        val p = parser.parse("<tool_call>{\"name\": \"a\", \"arguments\": \"{\\\"x\\\": 2}\"}</tool_call>")!!
        assertEquals(2, p.arguments.getInt("x"))
    }

    @Test
    fun recoversCallMissingClosingTag() {
        val p = parser.parse("<tool_call>\n{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 30}}")!!
        assertEquals("set_timer", p.name)
    }

    @Test
    fun keepsRawArgumentsVerbatim() {
        val p = parser.parse("<tool_call>\n{\"name\": \"a\", \"arguments\": {\"z\": 1, \"a\": \"है\"}}\n</tool_call>")!!
        assertEquals("{\"z\": 1, \"a\": \"है\"}", p.rawArguments)
    }

    @Test
    fun returnsNullForPlainText() {
        assertNull(parser.parse("Namaste! How can I help?"))
        assertNull(parser.parse("<tool_call>not json</tool_call>"))
    }
}
