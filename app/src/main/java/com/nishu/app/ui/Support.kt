package com.nishu.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Calendar
import java.util.Locale

@Suppress("UNCHECKED_CAST")
inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%d KB", bytes / 1000)
}

fun greetingFor(name: String, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String {
    val part = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    return if (name.isBlank()) "$part!" else "$part, $name!"
}

fun highlight(text: String, query: String, color: Color): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(q, i, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, hit))
            withStyle(SpanStyle(background = color)) { append(text.substring(hit, hit + q.length)) }
            i = hit + q.length
        }
    }
}
