package com.nishu.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** A conversation to open as soon as the UI is ready (from a notification tap, possibly on a cold start). */
object DeepLinks {
    const val EXTRA_OPEN_CONVERSATION = "open_conversation"
    val pendingConversation = MutableStateFlow<Long?>(null)

    fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_CONVERSATION, -1) ?: -1
        if (id > 0) pendingConversation.value = id
    }
}

object CompletionNotifier {
    private const val CHANNEL = "done"

    fun notifyDone(context: Context, conversationId: Long, title: String, ok: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Conversation ready", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, conversationId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(DeepLinks.EXTRA_OPEN_CONVERSATION, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (ok) "Your conversation is ready" else "Conversation saved")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(3000 + conversationId.toInt(), n)
    }
}
