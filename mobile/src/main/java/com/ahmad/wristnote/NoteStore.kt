package com.ahmad.wristnote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val text: String?, val audio: String, val failed: Boolean = false)

/** Tiny JSON-file store for notes. */
object NoteStore {
    @Volatile var version = 0L
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "notes.json")

    @Synchronized
    fun all(ctx: Context): List<Note> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.getLong("id"), if (o.isNull("text")) null else o.getString("text"),
                o.getString("audio"), o.optBoolean("failed"))
        }.sortedByDescending { it.id }
    }

    @Synchronized
    fun upsert(ctx: Context, note: Note) {
        val list = all(ctx).filter { it.id != note.id } + note
        save(ctx, list)
    }

    @Synchronized
    fun get(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        val list = all(ctx)
        list.firstOrNull { it.id == id }?.let { File(it.audio).delete() }
        save(ctx, list.filter { it.id != id })
    }

    private fun save(ctx: Context, list: List<Note>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text ?: JSONObject.NULL)
                .put("audio", it.audio).put("failed", it.failed))
        }
        val tmp = File(ctx.filesDir, "notes.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
        version++
    }
}
