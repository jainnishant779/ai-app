package com.nishu.app.summarize

/** Phrasing mirrors the trained summarization format ("Summarize ... in N bullet points:"). Do not reword casually. */
object Prompts {
    fun map(chunk: String) = "Summarize this conversation in 3 bullet points:\n\n$chunk"
    fun reduce(bullets: String) = "Summarize these notes in 5 bullet points:\n\n$bullets"
    fun extract(bullets: String) = "Extract the tasks and decisions from these notes as JSON:\n\n$bullets"
}
