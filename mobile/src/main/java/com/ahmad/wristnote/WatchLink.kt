package com.ahmad.wristnote

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Everything the phone sends to the watch over the Wear Data Layer. */
object WatchLink {
    const val PATH = "/nerdly/answer"
    const val SNAPSHOT = "/nerdly/notebooks"

    /** Where spoken replies come from. */
    enum class Voice { WATCH, PHONE, OFF }

    fun voice(ctx: Context): Voice = runCatching {
        Voice.valueOf(ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("v", Voice.WATCH.name)!!)
    }.getOrDefault(Voice.WATCH)

    fun setVoice(ctx: Context, v: Voice) =
        ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putString("v", v.name).apply()

    /** Shows an answer on the watch; spoken there or here depending on the setting. Call off the main thread. */
    fun send(ctx: Context, title: String, body: String, kind: String) {
        val speakable = kind == "answer" || kind == "say"
        val v = voice(ctx)
        val now = System.currentTimeMillis()
        val req = PutDataMapRequest.create("$PATH/$now").apply {
            dataMap.putString("title", title)
            dataMap.putString("body", body)
            dataMap.putString("kind", kind)
            dataMap.putBoolean("speak", speakable && v == Voice.WATCH)
            dataMap.putLong("time", now)
        }.asPutDataRequest().setUrgent()
        try {
            Tasks.await(Wearable.getDataClient(ctx).putDataItem(req), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("Nerdly", "could not reach the watch", e)
        }
        if (speakable && v == Voice.PHONE) Speaker.sayBlocking(ctx, "$title. $body")
    }

    /** A short spoken confirmation on the watch ("تمام، هفكّرك…"), no extra notification. */
    fun say(ctx: Context, text: String) {
        if (voice(ctx) != Voice.OFF) send(ctx, text, "", "say")
    }

    private val sync = Executors.newSingleThreadExecutor()

    /** Pushes notebooks and upcoming reminders so the watch can show them. */
    fun syncSoon(ctx: Context) {
        val app = ctx.applicationContext
        sync.execute {
            try {
                val books = JSONArray()
                Notebooks.all(app).forEach { nb ->
                    val b = JSONArray()
                    nb.blocks.forEach { b.put(JSONObject().put("id", it.id).put("h", it.heading).put("t", it.text).put("d", it.done)) }
                    books.put(JSONObject().put("id", nb.id).put("title", nb.title).put("blocks", b))
                }
                val rem = JSONArray()
                Reminders.upcoming(app).take(8).forEach {
                    rem.put(JSONObject().put("at", it.first).put("when", Fmt.whenText(it.first)).put("text", it.second))
                }
                val json = JSONObject().put("books", books).put("reminders", rem).toString()
                val req = PutDataMapRequest.create(SNAPSHOT).apply {
                    dataMap.putString("json", json)
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(app).putDataItem(req), 10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w("Nerdly", "snapshot sync failed", e)
            }
        }
    }
}
