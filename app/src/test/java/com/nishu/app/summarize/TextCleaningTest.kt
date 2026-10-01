package com.nishu.app.summarize

import com.nishu.app.stt.SttText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleaningTest {
    @Test
    fun summaryStripsMarkdownLabelsAndDuplicates() {
        val raw = "**Summarize**: The conversation covers the plan.\n" +
            "**Key Points**: The focus is on the unit test.\n" +
            "**Summary**: The conversation covers the plan.\n" +
            "- Second real point\n" +
            "1. Third point\n\n"
        assertEquals(
            "- The conversation covers the plan.\n- The focus is on the unit test.\n- Second real point\n- Third point",
            SummaryText.clean(raw),
        )
    }

    @Test
    fun summaryOfOnlyNoiseIsEmpty() {
        assertEquals("", SummaryText.clean("**  **\n---\n"))
    }

    @Test
    fun whisperAnnotationsAreNotTranscript() {
        assertNull(SttText.clean("["))
        assertNull(SttText.clean("[BLANK_AUDIO]"))
        assertNull(SttText.clean(" (music) "))
        assertNull(SttText.clean("*sigh*"))
        assertNull(SttText.clean("..."))
        assertEquals("hello there", SttText.clean("hello [pause] there"))
        assertEquals("send the deck", SttText.clean("send the deck ["))
    }
}
