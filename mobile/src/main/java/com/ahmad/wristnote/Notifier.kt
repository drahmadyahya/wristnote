package com.ahmad.wristnote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    private const val NOTES = "notes"
    private const val REMINDERS = "reminders"
    private const val WAITING_ID = 1

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTES, "الأفكار والأوامر", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun base(ctx: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ctx.getColor(R.color.ic_bg))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)

    /** A plain thought, with one-tap saving to the user's notes apps. */
    fun newIdea(ctx: Context, note: Note) {
        channels(ctx)
        val text = note.text ?: return
        val b = base(ctx, NOTES, "فكرة جديدة", text)
        Share.targets(ctx).forEachIndexed { i, (label, pkg) ->
            val pi = PendingIntent.getActivity(
                ctx, (note.id % 100000).toInt() * 10 + i, Share.intent(text, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "حفظ في $label", pi)
        }
        notify(ctx, (note.id % Int.MAX_VALUE).toInt(), b)
    }

    /** Confirms what a voice command did. */
    fun commandDone(ctx: Context, note: Note, ok: Boolean) {
        channels(ctx)
        val title = when (note.kind) {
            "alarm" -> if (ok) "اتظبط المنبه" else "المنبه محتاج خطوة"
            "reminder" -> "هفكّرك"
            "event" -> if (ok) "اتسجل في التقويم" else "التقويم محتاج إذن"
            "note" -> "اتضافت للنوتة"
            else -> "تم"
        }
        notify(ctx, (note.id % Int.MAX_VALUE).toInt(), base(ctx, NOTES, title, note.summary ?: note.text ?: ""))
    }

    fun tapToFinish(ctx: Context, title: String, text: String, intent: Intent) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, title.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify(ctx, title.hashCode(), base(ctx, NOTES, title, text).setContentIntent(pi))
    }

    fun reminderDue(ctx: Context, text: String, at: Long) {
        channels(ctx)
        val b = base(ctx, REMINDERS, "تذكير", text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        notify(ctx, (at / 1000 % Int.MAX_VALUE).toInt(), b)
    }

    fun waitingForModel(ctx: Context) {
        channels(ctx)
        notify(ctx, WAITING_ID, base(ctx, NOTES, "وصلت فكرة من الساعة", "افتح Nerdly Task ونزّل ملف التحويل لنص عشان تتكتب"))
    }

    private fun notify(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}
