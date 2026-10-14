package com.ahmad.wristnote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    private const val CHANNEL = "notes"
    private const val WAITING_ID = 1

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "ملاحظات جديدة", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun newNote(ctx: Context, note: Note) {
        ensureChannel(ctx)
        val text = note.text ?: return
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("فكرة جديدة من الساعة")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        Share.targets(ctx).forEachIndexed { i, (label, pkg) ->
            val pi = PendingIntent.getActivity(
                ctx, (note.id % 100000).toInt() * 10 + i, Share.intent(text, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "احفظ في $label", pi)
        }
        notify(ctx, (note.id % Int.MAX_VALUE).toInt(), b)
    }

    fun waitingForModel(ctx: Context) {
        ensureChannel(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("وصلت فكرة من الساعة")
            .setContentText("افتح التطبيق ونزّل ملف التحويل لنص مرة واحدة")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        notify(ctx, WAITING_ID, b)
    }

    private fun notify(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}
