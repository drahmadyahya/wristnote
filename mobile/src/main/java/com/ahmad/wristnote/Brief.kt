package com.ahmad.wristnote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The daily morning brief: today's schedule, open notebook items, yesterday's ideas. */
object Brief {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("brief", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun hour(ctx: Context) = prefs(ctx).getInt("h", 8)
    fun minute(ctx: Context) = prefs(ctx).getInt("m", 0)

    fun set(ctx: Context, on: Boolean, h: Int = hour(ctx), m: Int = minute(ctx)) {
        prefs(ctx).edit().putBoolean("on", on).putInt("h", h).putInt("m", m).apply()
        schedule(ctx)
    }

    fun timeText(ctx: Context): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour(ctx)); set(Calendar.MINUTE, minute(ctx)) }
        return Fmt.clock(c.timeInMillis)
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4242, Intent(ctx, BriefReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        if (!enabled(ctx)) { am.cancel(pi); return }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx)); set(Calendar.MINUTE, minute(ctx))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 5_000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    fun build(ctx: Context, now: Long): Answer {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val title = if (cal.get(Calendar.HOUR_OF_DAY) < 12) "صباح الخير" else "ملخص النهارده"
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val lines = mutableListOf<String>()
        lines += SimpleDateFormat("EEEE d MMMM", Locale("ar", "EG")).format(Date(now))

        val items = Answers.dayItems(ctx, dayStart).filter { it.allDay || it.at >= now - 30 * 60_000 }
        if (items.isEmpty()) lines += "مفيش مواعيد النهارده."
        else {
            lines += "النهارده عندك ${Answers.things(items.size)}:"
            items.take(4).forEach { lines += Answers.line(it) }
            if (items.size > 4) lines += "وكمان ${Fmt.num(items.size - 4)}"
        }

        val open = Notebooks.all(ctx).map { nb -> nb.title to nb.blocks.count { !it.heading && !it.done } }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        if (open.isNotEmpty()) {
            val total = open.sumOf { it.second }
            lines += "فاضل ${Fmt.num(total)} في نوتك: " +
                open.take(3).joinToString("، ") { "${it.first} (${Fmt.num(it.second)})" }
        }

        val yStart = dayStart - 86_400_000L
        val ideas = NoteStore.all(ctx).count { it.id in yStart until dayStart && it.kind == "idea" && it.text != null }
        if (ideas > 0) lines += "امبارح سجلت ${if (ideas == 1) "فكرة واحدة" else if (ideas == 2) "فكرتين" else "${Fmt.num(ideas)} أفكار"}."
        return Answer(title, lines.joinToString("\n"))
    }

    /** Builds and delivers the brief to the phone and the watch. Call off the main thread. */
    fun deliver(ctx: Context) {
        val b = build(ctx, System.currentTimeMillis())
        Notifier.brief(ctx, b.title, b.body)
        WatchLink.send(ctx, b.title, b.body, "brief")
    }
}

class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try { Brief.deliver(ctx.applicationContext) }
            catch (e: Exception) { Log.e("Nerdly", "brief failed", e) }
            finally { Brief.schedule(ctx.applicationContext); done.finish() }
        }.start()
    }
}
