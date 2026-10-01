package com.nishu.app.llm

/**
 * Hand-rolled port of the Qwen3 chat template (tools/qwen3_chat_template.jinja) with
 * enable_thinking=false. Messages passed in must not include the system message: the
 * fixed system prompt is always used. Assistant messages must not contain think blocks.
 */
class Qwen3PromptTemplate(private val systemPrompt: String) : PromptTemplate {

    override fun renderPrefix(): ByteArray =
        "<|im_start|>system\n$systemPrompt<|im_end|>\n".toByteArray(Charsets.UTF_8)

    override fun renderTurns(messages: List<ChatMessage>): ByteArray {
        require(messages.lastOrNull()?.role != Role.ASSISTANT) {
            "prompt must end on a user or tool message; a trailing assistant turn renders differently"
        }
        val sb = StringBuilder()
        messages.forEachIndexed { i, m ->
            when (m.role) {
                Role.SYSTEM -> error("system message must not be passed; the template owns it")
                Role.USER -> sb.append("<|im_start|>user\n").append(m.content).append("<|im_end|>\n")
                Role.ASSISTANT -> {
                    require("</think>" !in m.content) { "assistant history with think blocks is unsupported" }
                    sb.append("<|im_start|>assistant\n").append(m.content)
                    m.toolCall?.let { tc ->
                        if (m.content.isNotEmpty()) sb.append('\n')
                        sb.append("<tool_call>\n{\"name\": \"").append(tc.name)
                            .append("\", \"arguments\": ").append(tc.argumentsJson)
                            .append("}\n</tool_call>")
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.TOOL -> {
                    if (i == 0 || messages[i - 1].role != Role.TOOL) sb.append("<|im_start|>user")
                    sb.append("\n<tool_response>\n").append(m.content).append("\n</tool_response>")
                    if (i == messages.lastIndex || messages[i + 1].role != Role.TOOL) sb.append("<|im_end|>\n")
                }
            }
        }
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
