package com.ahmad.wristnote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Receives answers and the morning brief from the phone and shows them on the watch. */
class AnswerListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val changed = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        val client = Wearable.getDataClient(this)
        for (clip in changed.filter { it.uri.path?.startsWith("/nerdly/voice") == true }) {
            try { playClip(clip); Tasks.await(client.deleteDataItems(clip.uri)) }
            catch (e: Exception) { Log.e("Nerdly", "clip failed", e) }
        }
        val items = changed.filter { it.uri.path?.startsWith(PATH) == true }
        for (item in items) {
            try {
                val map = DataMapItem.fromDataItem(item).dataMap
                val title = map.getString("title") ?: continue
                val body = map.getString("body") ?: ""
                val kind = map.getString("kind") ?: "answer"
                val time = map.getLong("time", System.currentTimeMillis())
                // stale (e.g. delivered after a long disconnect): skip old answers, keep briefs for a few hours
                val maxAge = if (kind == "brief") 6 * 3_600_000L else 30 * 60_000L
                val fresh = System.currentTimeMillis() - time < maxAge
                if (fresh && kind != "say") show(title, body, kind, time)
                Tasks.await(client.deleteDataItems(item.uri))
                if (fresh && map.getBoolean("speak", false)) speak("$title. $body")
            } catch (e: Exception) {
                Log.e("Nerdly", "answer failed", e)
            }
        }
    }

    /** Reads the answer aloud; if this watch has no Arabic voice, ask the phone to read it. */
    private fun speak(text: String) {
        if (!Speaker.sayBlocking(this, text)) Speaker.askPhone(this, text)
    }

    /** A voice clip rendered by the phone. */
    private fun playClip(item: com.google.android.gms.wearable.DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        if (System.currentTimeMillis() - map.getLong("time", 0L) > 2 * 60_000) return  // too late to be useful
        val asset = map.getAsset("clip") ?: return
        val f = java.io.File(cacheDir, "clip.wav")
        Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        Speaker.playBlocking(this, f)
        f.delete()
    }

    private fun show(title: String, body: String, kind: String, time: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "الإجابات والملخص", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 80, 120)
        })
        val open = PendingIntent.getActivity(
            this, (time % 100000).toInt(),
            Intent(this, AnswerActivity::class.java)
                .putExtra("title", title).putExtra("body", body).putExtra("kind", kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setColor(getColor(R.color.ic_bg))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (kind == "brief") NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(if (kind == "brief") 2 else (time % 100000).toInt() + 10, n)
        } catch (_: SecurityException) {}
    }

    companion object {
        const val PATH = "/nerdly/answer"
        private const val CHANNEL = "answers"
    }
}
