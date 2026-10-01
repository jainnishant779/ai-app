package com.nishu.app.llm

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(val name: String, val argumentsJson: String)

data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCall: ToolCall? = null,
)
