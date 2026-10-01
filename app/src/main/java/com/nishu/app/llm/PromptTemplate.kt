package com.nishu.app.llm

/** Renders the byte-exact Qwen3 prompt the model was trained on. See docs/CONTRACT.md. */
interface PromptTemplate {
    /** `<|im_start|>system ... <|im_end|>\n`. Constant, so it can be KV-cached. */
    fun renderPrefix(): ByteArray

    /** Everything after the system message, ending with the empty-think generation prompt. */
    fun renderTurns(messages: List<ChatMessage>): ByteArray

    fun render(messages: List<ChatMessage>): ByteArray = renderPrefix() + renderTurns(messages)
}
