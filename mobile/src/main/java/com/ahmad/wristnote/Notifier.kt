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
    // new id: channel sound/vibration can't be changed after a channel is created
    private const val REMINDERS = "reminders_sound"
    private const val WAITING_ID = 1
    private const val BRIEF_ID = 2

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTES, "الأفكار والأوامر", NotificationManager.IMPORTANCE_DEFAULT))
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(NotificationChannel(REMINDERS, "التذكيرات", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تذكيرات «فكرني» في وقتها، بصوت واهتزاز"
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            enableLights(true)
            lightColor = ctx.getColor(R.color.ic_bg)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
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
        if (note.kind == "answer") {
            // the watch shows its own copy, so keep this one on the phone
            val lines = (note.summary ?: "").split("\n", limit = 2)
            notify(ctx, (note.id % Int.MAX_VALUE).toInt(),
                base(ctx, NOTES, lines[0], lines.getOrElse(1) { "" }).setLocalOnly(true))
            return
        }
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
        val id = (at / 1000 % Int.MAX_VALUE).toInt()
        fun action(kind: String) = PendingIntent.getBroadcast(
            ctx, id * 2 + if (kind == "snooze") 1 else 0,
            Intent(ctx, ReminderReceiver::class.java).setAction(kind)
                .putExtra("text", text).putExtra("at", at).putExtra("nid", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = base(ctx, REMINDERS, "تذكير", text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "تم", action("done"))
            .addAction(0, "أجّل ١٠ دقايق", action("snooze"))
        notify(ctx, id, b)
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    /** Quiet ongoing notification while a thought is being transcribed. */
    fun working(ctx: Context): android.app.Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", "التحويل لنص", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(ctx, "work")
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ctx.getColor(R.color.ic_bg))
            .setContentTitle("بحوّل فكرتك لنص…")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun brief(ctx: Context, title: String, body: String) {
        channels(ctx)
        notify(ctx, BRIEF_ID, base(ctx, NOTES, title, body).setLocalOnly(true))
    }

    fun waitingForModel(ctx: Context) {
        channels(ctx)
        notify(ctx, WAITING_ID, base(ctx, NOTES, "وصلت فكرة من الساعة", "افتح Nerdly Task ونزّل ملف التحويل لنص عشان تتكتب"))
    }

    private fun notify(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}
