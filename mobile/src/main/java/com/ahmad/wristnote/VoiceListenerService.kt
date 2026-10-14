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
        val items = events.filter {
            it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(PREFIX) == true
        }.map { it.dataItem.freeze() }

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

    companion object { const val PREFIX = "/wristnote/voice" }
}
