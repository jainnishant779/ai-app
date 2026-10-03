package com.nishu.app.summarize

/**
 * Phrasing mirrors the trained summarization format ("Summarize ... in N bullet points:"): all 490 summarization
 * examples the Nishu model was fine-tuned on use it. Do not reword casually; a reworded prompt with
 * "facts, decisions, or actions" made the model label a greeting "Decision: ..." on the phone.
 */
object Prompts {
    fun map(chunk: String) = "Summarize this conversation in 3 bullet points:\n\n$chunk"
    fun reduce(bullets: String) = "Summarize these notes in 5 bullet points:\n\n$bullets"
    fun extract(bullets: String) = "Extract the tasks and decisions from these notes as JSON:\n\n$bullets"
}
