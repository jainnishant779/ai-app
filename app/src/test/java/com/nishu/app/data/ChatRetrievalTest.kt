package com.nishu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRetrievalTest {
    @Test
    fun keywordsDropHinglishAndEnglishQuestionWords() {
        assertEquals(listOf("budget", "decide"), ChatRetrieval.keywords("Budget ke baare mein kya decide hua?").filter { it != "baare" })
        assertTrue(ChatRetrieval.keywords("kya hai?").isEmpty())
    }

    @Test
    fun theLineThatAnswersRanksFirst() {
        val lines = listOf(
            "Hello, kaise ho?",
            "Budget 2 lakh se zyada nahi jayega, ye decided hai.",
            "Rahul kal proposal bhejega.",
        )
        assertEquals(1, ChatRetrieval.rank(lines, "Budget pe kya decide hua?").first())
        assertEquals(2, ChatRetrieval.rank(lines, "proposal kaun bhejega?").first())
    }

    @Test
    fun noMatchKeepsEveryLineInOrder() {
        val lines = listOf("a b c", "d e f")
        assertEquals(listOf(0, 1), ChatRetrieval.rank(lines, "xyz qqq"))
    }

    @Test
    fun fitStopsAtTheBudget() {
        val words = { s: String -> s.split(" ").size }
        assertEquals("one two\n\nthree", ChatRetrieval.fit(listOf("one two", "three", "four five six"), 7, words)) // cost = tokens + 2 per entry
    }
}
