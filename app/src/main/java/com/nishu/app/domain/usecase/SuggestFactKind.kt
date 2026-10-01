package com.nishu.app.domain.usecase

import com.nishu.app.domain.model.MemoryKind

private val contact = Regex(
    "\b(mom|mummy|papa|dad|bhai|bhaiya|didi|dadi|nani|uncle|aunty|number|phone|lives in|rehti|rehta)\b",
    RegexOption.IGNORE_CASE,
)
private val preference = Regex(
    "\b(like|love|prefer|pasand|favourite|favorite|hate|nahi pasand)\b",
    RegexOption.IGNORE_CASE,
)

fun suggestFactKind(text: String): MemoryKind = when {
    contact.containsMatchIn(text) -> MemoryKind.CONTACT
    preference.containsMatchIn(text) -> MemoryKind.PREFERENCE
    else -> MemoryKind.FACT
}
