package com.nishu.app.llm

import com.nishu.app.data.QuestionAnswerer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate

/** Single-shot answer over the top retrieved snippets. Not a chat: no history, ~400 tokens of context at most. */
class LlmQuestionAnswerer : QuestionAnswerer {
    override fun answer(question: String, context: List<String>): Flow<String> = channelFlow {
        if (context.isEmpty()) {
            send("I couldn't find anything about that in your conversations or memory.")
            return@channelFlow
        }
        try {
            EngineHolder.withEngine { engine ->
                val prompt = buildPrompt(engine, question, context)
                var acc = ""
                val r = engine.generate(listOf(ChatMessage(Role.USER, prompt)), SamplerProfile.Chat(), 160) { piece ->
                    acc += piece
                    trySend(acc)
                }
                send(r.text.trim().ifEmpty { "I couldn't come up with an answer." })
            }
        } catch (e: ModelMissingException) {
            send("The on-device model is not installed yet.")
        }
    }.conflate()

    private fun buildPrompt(engine: LLMEngine, question: String, context: List<String>): String {
        val header = "Neeche diye transcript ke hisaab se jawab do:\n\n"
        val footer = "\n\nSawal: $question"
        val budget = 400
        val kept = StringBuilder()
        for (c in context) {
            val candidate = if (kept.isEmpty()) c else "$kept\n$c"
            if (kept.isNotEmpty() && engine.tokenCount(candidate) > budget) break
            kept.clear()
            kept.append(candidate)
        }
        return header + kept + footer
    }
}
