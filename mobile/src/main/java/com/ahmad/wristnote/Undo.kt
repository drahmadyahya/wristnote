package com.ahmad.wristnote

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONObject
import java.util.Calendar

/**
 * Takes back what a voice command did: the calendar event, the reminder, the Clock alarm,
 * or the items it added to a notebook. Used when the note is deleted, marked as a plain
 * idea, or re-run.
 */
object Undo {

    /** Readable "what else goes" for the delete confirmation; null when there's nothing to remove. */
    fun describe(ctx: Context, note: Note): String? {
        val u = info(ctx, note) ?: return null
        return when (u.optString("t")) {
            "event" -> "المعاد من التقويم"
            "reminder" -> if (u.optLong("at") > System.currentTimeMillis()) "التذكير" else null
            "alarm" -> if (u.optLong("at") > System.currentTimeMillis()) "المنبه" else null
            "note" -> {
                val nb = Notebooks.get(ctx, u.optLong("nb")) ?: return null
                val ids = ids(u)
                val n = nb.blocks.count { it.id in ids }
                if (n == 0) null else if (u.optBoolean("newNb") && nb.blocks.all { it.id in ids }) "نوتة «${nb.title}»"
                else "اللي اتضاف في «${nb.title}»"
            }
            else -> null
        }
    }

    /** Removes what the command made. Returns a short line saying what happened (null = nothing to do). */
    fun revert(ctx: Context, note: Note): String? {
        val u = info(ctx, note) ?: return null
        return try {
            when (u.optString("t")) {
                "event" -> if (deleteEvent(ctx, u)) "اتمسح المعاد من التقويم" else "ملقيناش المعاد في التقويم"
                "reminder" -> { Reminders.cancel(ctx, u.optLong("at"), u.optString("text", null)); "اتلغى التذكير" }
                "alarm" -> if (dismissAlarm(ctx, u.optLong("at"))) null else "فتحتلك المنبهات، امسح منبه ${Fmt.clock(u.optLong("at"))}"
                "note" -> { removeBlocks(ctx, u); "اتشال من النوتة" }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("Nerdly", "undo failed", e); null
        }
    }

    /**
     * The saved record, or — for notes made before 4.2 — rebuilt by re-reading the sentence
     * as of the moment it was said (same parser, same result).
     */
    private fun info(ctx: Context, note: Note): JSONObject? {
        note.undo?.let { return try { JSONObject(it) } catch (_: Exception) { null } }
        if (note.kind !in setOf("event", "reminder", "alarm")) return null
        val cmd = try {
            CommandParser.parse(note.text ?: return null, Notebooks.all(ctx).map { it.ref() }, note.id)
        } catch (_: Exception) { null }
        return when (cmd) {
            is Command.Event -> JSONObject().put("t", "event").put("id", -1L).put("title", cmd.title).put("timed", cmd.timed).put("at", cmd.at)
            is Command.Reminder -> JSONObject().put("t", "reminder").put("at", cmd.at).put("text", cmd.text)
            is Command.Alarm -> JSONObject().put("t", "alarm").put("at", cmd.at)
            else -> null
        }
    }

    private fun ids(u: JSONObject): Set<Long> {
        val a = u.optJSONArray("blocks") ?: return emptySet()
        return (0 until a.length()).map { a.getLong(it) }.toSet()
    }

    // ───── calendar ─────
    private fun deleteEvent(ctx: Context, u: JSONObject): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        val cr = ctx.contentResolver
        val id = u.optLong("id", -1L)
        if (id > 0 && cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0) return true
        // no id (older notes) or it moved: find it by title and start time
        val title = u.optString("title")
        val at = u.optLong("at")
        val (from, to) = if (u.optBoolean("timed", true)) (at - 60_000) to (at + 60_000) else {
            val l = Calendar.getInstance().apply { timeInMillis = at }
            val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
            (utc - 60_000) to (utc + 60_000)
        }
        var n = 0
        cr.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=? AND ${CalendarContract.Events.DELETED}=0",
            arrayOf(title, from.toString(), to.toString()), null)?.use { c ->
            while (c.moveToNext()) n += cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.getLong(0)), null, null)
        }
        return n > 0
    }

    // ───── Clock app alarm ─────
    /**
     * Android gives other apps no way to delete a Clock alarm ("dismiss" was tested and
     * does nothing for future alarms), so open the Clock's alarm list: one tap to delete.
     */
    private fun dismissAlarm(ctx: Context, at: Long): Boolean {
        if (at < System.currentTimeMillis()) return true // already rang
        val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { if (ctx is android.app.Activity) ctx.startActivity(i) else Actions.startFromBackground(ctx, i) } catch (_: Exception) {}
        return false
    }

    // ───── notebook ─────
    private fun removeBlocks(ctx: Context, u: JSONObject) {
        val nbId = u.optLong("nb")
        val nb = Notebooks.get(ctx, nbId) ?: return
        val ids = ids(u)
        val left = nb.blocks.filter { it.id !in ids }
        // a notebook this command made, with nothing else added since: remove it entirely
        if (u.optBoolean("newNb") && left.isEmpty()) Notebooks.delete(ctx, nbId)
        else Notebooks.put(ctx, nb.copy(blocks = left))
        WatchLink.syncSoon(ctx)
    }
}
