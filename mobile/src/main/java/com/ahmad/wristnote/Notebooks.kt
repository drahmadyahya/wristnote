package com.ahmad.wristnote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A block inside a notebook: a heading, or a checkable item. */
data class Block(val id: Long, val heading: Boolean, val text: String, val done: Boolean = false)

data class Notebook(val id: Long, val title: String, val blocks: List<Block>) {
    fun ref() = NotebookRef(id, title, blocks.filter { it.heading }.map { it.id to it.text },
        blocks.filter { !it.heading }.map { it.id to it.text })
    val itemCount get() = blocks.count { !it.heading }
}

/** User notebooks (created on the phone), filled by voice or by hand. */
object Notebooks {
    @Volatile var version = 0L
        private set
    private var seq = System.currentTimeMillis()

    @Synchronized fun newId(): Long { seq = maxOf(seq + 1, System.currentTimeMillis()); return seq }

    private fun file(ctx: Context) = File(ctx.filesDir, "notebooks.json")

    @Synchronized
    fun all(ctx: Context): List<Notebook> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("blocks")
            Notebook(o.getLong("id"), o.getString("title"), (0 until b.length()).map { j ->
                val x = b.getJSONObject(j)
                Block(x.getLong("id"), x.getBoolean("h"), x.getString("t"), x.optBoolean("d"))
            })
        }
    }

    @Synchronized fun get(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun put(ctx: Context, nb: Notebook) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == nb.id }
        if (i >= 0) list[i] = nb else list.add(0, nb)
        save(ctx, list)
    }

    @Synchronized fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /**
     * Adds an item. anchor = a heading (goes at the end of its section),
     * an item (goes right after it), or null (end of notebook).
     * newHeading creates that heading first (or reuses one with the same name).
     */
    @Synchronized
    fun addItem(ctx: Context, notebookId: Long, anchorId: Long?, text: String, newHeading: String? = null): Notebook? {
        val nb = get(ctx, notebookId) ?: return null
        val blocks = nb.blocks.toMutableList()
        var anchor = anchorId
        if (newHeading != null) {
            val existing = blocks.firstOrNull { it.heading && Ar.key(Ar.token(it.text)) == Ar.key(Ar.token(newHeading)) }
            anchor = existing?.id ?: Block(newId(), true, newHeading).also { blocks.add(it) }.id
        }
        val item = Block(newId(), false, text)
        val i = blocks.indexOfFirst { it.id == anchor }
        when {
            i < 0 -> blocks.add(item)
            blocks[i].heading -> {
                var end = i + 1
                while (end < blocks.size && !blocks[end].heading) end++
                blocks.add(end, item)
            }
            else -> blocks.add(i + 1, item)
        }
        return nb.copy(blocks = blocks).also { put(ctx, it) }
    }

    private fun save(ctx: Context, list: List<Notebook>) {
        val arr = JSONArray()
        list.forEach { nb ->
            val b = JSONArray()
            nb.blocks.forEach { b.put(JSONObject().put("id", it.id).put("h", it.heading).put("t", it.text).put("d", it.done)) }
            arr.put(JSONObject().put("id", nb.id).put("title", nb.title).put("blocks", b))
        }
        val tmp = File(ctx.filesDir, "notebooks.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
        version++
    }
}
