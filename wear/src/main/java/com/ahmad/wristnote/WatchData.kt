package com.ahmad.wristnote

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WBlock(val id: Long, val heading: Boolean, val text: String, val done: Boolean)
data class WBook(val id: Long, val title: String, val blocks: List<WBlock>) {
    val pending get() = blocks.count { !it.heading && !it.done }
}
data class WReminder(val whenText: String, val text: String)
data class Snapshot(val books: List<WBook>, val reminders: List<WReminder>)

/** The notebooks/reminders copy the phone keeps on the watch, plus ticking items from the watch. */
object WatchData {
    private const val PATH = "/nerdly/notebooks"

    fun cached(ctx: Context): Snapshot? =
        ctx.getSharedPreferences("snapshot", Context.MODE_PRIVATE).getString("json", null)?.let { parse(it) }

    /** Latest copy from the Data Layer (works offline once synced). Call off the main thread. */
    fun load(ctx: Context): Snapshot? {
        return try {
            val items = Tasks.await(Wearable.getDataClient(ctx).getDataItems(Uri.parse("wear://*$PATH")), 5, TimeUnit.SECONDS)
            val json = items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString("json") }
            items.release()
            if (json != null) ctx.getSharedPreferences("snapshot", Context.MODE_PRIVATE).edit().putString("json", json).apply()
            json?.let { parse(it) } ?: cached(ctx)
        } catch (e: Exception) { cached(ctx) }
    }

    fun toggle(ctx: Context, book: Long, block: Long, done: Boolean) {
        val now = System.currentTimeMillis()
        val req = PutDataMapRequest.create("/nerdly/toggle/$now").apply {
            dataMap.putLong("nb", book); dataMap.putLong("block", block)
            dataMap.putBoolean("done", done); dataMap.putLong("time", now)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req)
        // keep the local copy in step so the screen stays right before the phone answers
        val snap = cached(ctx) ?: return
        val updated = snap.copy(books = snap.books.map { b ->
            if (b.id != book) b else b.copy(blocks = b.blocks.map { if (it.id == block) it.copy(done = done) else it })
        })
        ctx.getSharedPreferences("snapshot", Context.MODE_PRIVATE).edit().putString("json", toJson(updated)).apply()
    }

    private fun parse(json: String): Snapshot? = try {
        val o = JSONObject(json)
        val books = o.getJSONArray("books").let { a ->
            (0 until a.length()).map { i ->
                val b = a.getJSONObject(i); val bl = b.getJSONArray("blocks")
                WBook(b.getLong("id"), b.getString("title"), (0 until bl.length()).map { j ->
                    val x = bl.getJSONObject(j)
                    WBlock(x.getLong("id"), x.getBoolean("h"), x.getString("t"), x.optBoolean("d"))
                })
            }
        }
        val rem = o.optJSONArray("reminders")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { r -> WReminder(r.getString("when"), r.getString("text")) } }
        } ?: emptyList()
        Snapshot(books, rem)
    } catch (e: Exception) { null }

    private fun toJson(s: Snapshot): String {
        val books = org.json.JSONArray()
        s.books.forEach { b ->
            val bl = org.json.JSONArray()
            b.blocks.forEach { bl.put(JSONObject().put("id", it.id).put("h", it.heading).put("t", it.text).put("d", it.done)) }
            books.put(JSONObject().put("id", b.id).put("title", b.title).put("blocks", bl))
        }
        val rem = org.json.JSONArray()
        s.reminders.forEach { rem.put(JSONObject().put("when", it.whenText).put("text", it.text)) }
        return JSONObject().put("books", books).put("reminders", rem).toString()
    }
}
