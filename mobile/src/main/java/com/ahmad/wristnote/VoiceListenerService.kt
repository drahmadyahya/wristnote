package com.ahmad.wristnote

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/** Receives voice clips from the watch (Data Layer), saves them, queues transcription. */
class VoiceListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val client = Wearable.getDataClient(this)
        val changed = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        for (item in changed) {
            val path = item.uri.path ?: continue
            try {
                when {
                    path.startsWith(TOGGLE) -> toggle(item)
                    path.startsWith(TTS_FAIL) -> speakForWatch(item)
                    else -> continue
                }
                Tasks.await(client.deleteDataItems(item.uri))
            } catch (e: Exception) { Log.e("Nerdly", "watch request failed", e) }
        }
        val items = changed.filter { it.uri.path?.startsWith(PREFIX) == true }

        for (item in items) {
            try {
                val map = DataMapItem.fromDataItem(item).dataMap
                val time = map.getLong("time", System.currentTimeMillis())
                val asset = map.getAsset("audio") ?: continue
                val dir = File(filesDir, "audio").apply { mkdirs() }
                val out = File(dir, "$time.wav")
                if (!out.exists()) {
                    val fd = Tasks.await(client.getFdForAsset(asset))
                    fd.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                if (NoteStore.get(this, time) == null) {
                    NoteStore.upsert(this, Note(time, null, out.absolutePath))
                }
                TranscribeWorker.enqueue(this, time)
                Tasks.await(client.deleteDataItems(item.uri))
            } catch (e: Exception) {
                Log.e("WristNote", "failed to receive clip", e)
            }
        }
    }

    /** Ticked/unticked on the watch. */
    private fun toggle(item: com.google.android.gms.wearable.DataItem) {
        val m = DataMapItem.fromDataItem(item).dataMap
        val nb = Notebooks.get(this, m.getLong("nb")) ?: return
        val id = m.getLong("block"); val done = m.getBoolean("done")
        Notebooks.put(this, nb.copy(blocks = nb.blocks.map { if (it.id == id) it.copy(done = done) else it }))
    }

    /** The watch has no Arabic voice: read the answer here instead (once a hint, then just speak). */
    private fun speakForWatch(item: com.google.android.gms.wearable.DataItem) {
        val text = DataMapItem.fromDataItem(item).dataMap.getString("text") ?: return
        // best: render the Arabic voice here and let the watch play it on its own speaker
        val clip = Speaker.synthBytes(this, text)
        if (clip != null) {
            val now = System.currentTimeMillis()
            val req = com.google.android.gms.wearable.PutDataMapRequest.create("$VOICE/$now").apply {
                dataMap.putAsset("clip", com.google.android.gms.wearable.Asset.createFromBytes(clip))
                dataMap.putLong("time", now)
            }.asPutDataRequest().setUrgent()
            Tasks.await(Wearable.getDataClient(this).putDataItem(req), 10, java.util.concurrent.TimeUnit.SECONDS)
            return
        }
        // no Arabic voice on the phone either: say so once
        val prefs = getSharedPreferences("voice", MODE_PRIVATE)
        if (!prefs.getBoolean("hinted_none", false)) {
            prefs.edit().putBoolean("hinted_none", true).apply()
            Notifier.plain(this, "مفيش صوت عربي",
                "نزّل الصوت العربي من إعدادات الموبايل ← الإدارة العامة ← تحويل النص إلى كلام، عشان الساعة ترد بصوت.")
        }
    }

    companion object {
        const val PREFIX = "/wristnote/voice"
        const val TOGGLE = "/nerdly/toggle"
        const val TTS_FAIL = "/nerdly/ttsfail"
        const val VOICE = "/nerdly/voice"
    }
}
