package com.ahmad.wristnote

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Carries out a parsed voice command. Returns (kind, summary, ok). */
object Actions {

    data class Result(val kind: String, val summary: String, val ok: Boolean)

    fun run(ctx: Context, cmd: Command): Result = when (cmd) {
        is Command.Alarm -> alarm(ctx, cmd)
        is Command.Reminder -> reminder(ctx, cmd)
        is Command.Event -> event(ctx, cmd)
        is Command.AddToNote -> addToNote(ctx, cmd)
        is Command.CreateNotebook -> createNotebook(ctx, cmd)
        is Command.NoteNotFound -> Result("idea",
            if (cmd.name.isBlank()) "مفيش نوتة بالاسم ده، اتحفظت كفكرة" else "مفيش نوتة اسمها «${cmd.name}»، اتحفظت كفكرة", false)
    }

    // ───── alarm: hands the time to the phone's Clock app ─────
    private fun alarm(ctx: Context, c: Command.Alarm): Result {
        val cal = Calendar.getInstance().apply { timeInMillis = c.at }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_MESSAGE, c.label.ifBlank { "Nerdly Task" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val summary = "منبه ${Fmt.whenText(c.at)}"
        val ok = startFromBackground(ctx, intent)
        if (!ok) Notifier.tapToFinish(ctx, "اضغط عشان يتظبط المنبه", summary, intent)
        return Result("alarm", summary, ok)
    }

    /**
     * Android blocks background apps from opening other apps. With
     * "Display over other apps" allowed, a brief invisible overlay window
     * makes the launch permitted (required on Android 15+).
     */
    private fun startFromBackground(ctx: Context, intent: Intent): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val wm = ctx.getSystemService(WindowManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var ok = false
        main.post {
            val v = View(ctx)
            try {
                wm.addView(v, WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT))
                ctx.startActivity(intent)
                ok = true
            } catch (_: Exception) {
            } finally {
                main.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, 2500)
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    // ───── reminder: our own exact notification ─────
    private fun reminder(ctx: Context, c: Command.Reminder): Result {
        Reminders.schedule(ctx, c.at, c.text)
        return Result("reminder", "هفكّرك ${Fmt.whenText(c.at)}", true)
    }

    // ───── calendar event ─────
    private fun event(ctx: Context, c: Command.Event): Result {
        val summary = "${c.title}، ${if (c.timed) Fmt.whenText(c.at) else Fmt.dayLabel(c.at)}"
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return Result("event", "محتاج إذن التقويم: $summary", false)
        }
        val calId = calendarId(ctx) ?: return Result("event", "مفيش تقويم على الموبايل: $summary", false)
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, c.title)
            if (c.timed) {
                put(CalendarContract.Events.DTSTART, c.at)
                put(CalendarContract.Events.DTEND, c.at + 60 * 60_000)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            } else {
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    val l = Calendar.getInstance().apply { timeInMillis = c.at }
                    clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
                }
                put(CalendarContract.Events.DTSTART, utc.timeInMillis)
                put(CalendarContract.Events.DTEND, utc.timeInMillis + 86_400_000)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?: return Result("event", "متسجلش في التقويم: $summary", false)
        val eventId = uri.lastPathSegment?.toLongOrNull()
        if (eventId != null && c.timed) {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return Result("event", "اتسجل في التقويم: $summary", true)
    }

    /** Primary writable calendar (Samsung or Google account). */
    private fun calendarId(ctx: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val sel = "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            var best: Long? = null
            var bestScore = -1
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val primary = c.getInt(1) == 1
                val type = c.getString(2) ?: ""
                val score = (if (primary) 4 else 0) + (if (type == "com.google") 2 else 0) + (if (type.contains("samsung")) 1 else 0)
                if (score > bestScore) { best = id; bestScore = score }
            }
            return best
        }
        return null
    }

    // ───── create notebook ─────
    private fun createNotebook(ctx: Context, c: Command.CreateNotebook): Result {
        val key = Ar.key(Ar.token(c.title))
        val existing = Notebooks.all(ctx).firstOrNull { nb ->
            Ar.key(Ar.token(nb.title)).let { it == key || Ar.close(it, key) }
        }
        val nb = existing ?: Notebook(Notebooks.newId(), c.title, emptyList()).also { Notebooks.put(ctx, it) }
        val heading = c.heading
        for (item in c.items) Notebooks.addItem(ctx, nb.id, null, item, heading)
        if (c.items.isEmpty() && heading != null) {
            val cur = Notebooks.get(ctx, nb.id)!!
            Notebooks.put(ctx, cur.copy(blocks = cur.blocks + Block(Notebooks.newId(), true, heading)))
        }
        val count = if (c.items.isEmpty()) "" else " وفيها ${itemsText(c.items.size)}"
        val summary = if (existing != null) "النوتة «${nb.title}» موجودة$count" else "اتعملت نوتة «${nb.title}»$count"
        return Result("note", summary, true)
    }

    private fun itemsText(n: Int) = when (n) { 1 -> "بند واحد"; 2 -> "بندين"; in 3..10 -> "${Fmt.num(n)} بنود"; else -> "${Fmt.num(n)} بند" }

    // ───── add to notebook ─────
    private fun addToNote(ctx: Context, c: Command.AddToNote): Result {
        val items = CommandParser.splitItems(c.text)
        var nb: Notebook? = null
        var anchor = c.anchorId
        for (item in items) {
            nb = Notebooks.addItem(ctx, c.notebookId, anchor, item, if (anchor == c.anchorId) c.newHeading else null)
                ?: return Result("note", "النوتة اتمسحت", false)
            // keep later items in order, right after the previous one
            anchor = nb.blocks.lastOrNull { !it.heading && it.text == item }?.id
        }
        val book = nb ?: return Result("note", "مفيش حاجة تتضاف", false)
        // find the heading the new item ended up under
        val idx = book.blocks.indexOfLast { !it.heading && it.text == items.last() }
        val heading = (idx downTo 0).firstOrNull { book.blocks[it].heading }?.let { book.blocks[it].text }
        val where = if (heading != null) "${book.title} ← $heading" else book.title
        val extra = if (c.newHeading != null && heading == c.newHeading) " (عنوان جديد)" else ""
        return Result("note", "اتضافت في $where$extra", true)
    }
}

/** Exact-time reminders that survive reboots. */
object Reminders {
    private fun file(ctx: Context) = File(ctx.filesDir, "reminders.json")

    @Synchronized
    private fun load(ctx: Context): MutableList<Pair<Long, String>> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val a = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getLong("at") to o.getString("text") } }.toMutableList()
    }

    @Synchronized
    private fun store(ctx: Context, list: List<Pair<Long, String>>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("at", it.first).put("text", it.second)) }
        file(ctx).writeText(a.toString())
    }

    fun schedule(ctx: Context, at: Long, text: String) {
        val list = load(ctx).filter { it.first > System.currentTimeMillis() - 60_000 }.toMutableList()
        list.add(at to text); store(ctx, list)
        arm(ctx, at, text)
    }

    private fun arm(ctx: Context, at: Long, text: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, (at / 1000 % Int.MAX_VALUE).toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("text", text).putExtra("at", at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun rearmAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val list = load(ctx).filter { it.first > now - 60_000 }
        store(ctx, list)
        list.forEach { arm(ctx, maxOf(it.first, now + 5_000), it.second) }
    }

    fun done(ctx: Context, at: Long) = store(ctx, load(ctx).filter { it.first != at })
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        Notifier.reminderDue(ctx, text, intent.getLongExtra("at", 0L))
        Reminders.done(ctx, intent.getLongExtra("at", 0L))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") {
            Reminders.rearmAll(ctx)
        }
    }
}
