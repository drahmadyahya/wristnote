package com.ahmad.wristnote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.JulianFields
import java.util.Date
import java.util.Locale

/** A short answer sized for a round watch screen. */
data class Answer(val title: String, val body: String)

/** Builds answers to spoken questions from the calendar, reminders, notebooks and past ideas. */
object Answers {
    private val eg = Locale("ar", "EG")
    private const val DAY = 86_400_000L

    fun build(ctx: Context, ask: Command.Ask, now: Long): Answer = when (ask.what) {
        AskKind.AGENDA -> agenda(ctx, ask.dayStart)
        AskKind.NOTEBOOK -> notebook(ctx, ask.notebookId)
        AskKind.LAST_IDEA -> lastIdea(ctx)
        AskKind.REMINDERS -> reminders(ctx, now)
        AskKind.BRIEF -> Brief.build(ctx, now)
        AskKind.DATE -> Answer("النهارده", SimpleDateFormat("EEEE d MMMM yyyy", eg).format(Date(now)))
        AskKind.TIME -> Answer("الساعة", Fmt.clock(now))
    }

    data class Item(val at: Long, val allDay: Boolean, val text: String, val reminder: Boolean)

    /** Calendar events and our own reminders on one local day. */
    fun dayItems(ctx: Context, dayStart: Long): List<Item> {
        val list = mutableListOf<Item>()
        list += events(ctx, dayStart)
        list += Reminders.upcoming(ctx).filter { it.first in dayStart until dayStart + DAY }
            .map { Item(it.first, false, it.second, true) }
        return list.sortedWith(compareBy({ !it.allDay }, { it.at }))
    }

    fun line(i: Item) = "• " + (if (i.allDay) "طول اليوم" else Fmt.clock(i.at)) + "  " +
        (if (i.reminder) "تذكير: " else "") + i.text

    fun canReadCalendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun events(ctx: Context, dayStart: Long): List<Item> {
        if (!canReadCalendar(ctx)) return emptyList()
        // by-day query handles all-day events and time zones correctly
        val julian = Instant.ofEpochMilli(dayStart).atZone(ZoneId.systemDefault()).toLocalDate()
            .getLong(JulianFields.JULIAN_DAY)
        val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon()
            .appendPath(julian.toString()).appendPath(julian.toString()).build()
        val proj = arrayOf(
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.VISIBLE,
        )
        val out = mutableListOf<Item>()
        try {
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 0) continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "معاد"
                    out += Item(c.getLong(0), c.getInt(1) == 1, title, false)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun things(n: Int) = when {
        n == 1 -> "حاجة واحدة"; n == 2 -> "حاجتين"
        n in 3..10 -> "${Fmt.num(n)} حاجات"; else -> "${Fmt.num(n)} حاجة"
    }

    private fun agenda(ctx: Context, dayStart: Long): Answer {
        val day = Fmt.dayLabel(dayStart + 12 * 3_600_000L)
        val items = dayItems(ctx, dayStart)
        if (items.isEmpty()) {
            val hint = if (canReadCalendar(ctx)) "" else "\nفعّل إذن التقويم من الإعدادات عشان أشوف مواعيدك."
            return Answer("$day فاضي", "مفيش مواعيد ولا تذكيرات.$hint")
        }
        val shown = items.take(6).map(::line).toMutableList()
        if (items.size > 6) shown += "و${things(items.size - 6)} كمان"
        return Answer("$day: ${things(items.size)}", shown.joinToString("\n"))
    }

    private fun notebook(ctx: Context, id: Long): Answer {
        val nb = Notebooks.get(ctx, id) ?: return Answer("مش لاقي النوتة", "يمكن اتمسحت.")
        val lines = mutableListOf<String>()
        var heading: String? = null
        var headingShown = false
        var pending = 0
        for (b in nb.blocks) {
            if (b.heading) { heading = b.text; headingShown = false; continue }
            if (b.done) continue
            pending++
            if (lines.size >= 12) continue
            if (heading != null && !headingShown) { lines += "$heading:"; headingShown = true }
            lines += "• ${b.text}"
        }
        if (pending == 0) {
            return Answer(nb.title, if (nb.itemCount == 0) "النوتة فاضية." else "كل البنود اتعملت.")
        }
        if (pending > lines.count { it.startsWith("•") }) lines += "وكمان ${Fmt.num(pending - lines.count { it.startsWith("•") })}"
        return Answer("فاضل ${Fmt.num(pending)} في ${nb.title}", lines.joinToString("\n"))
    }

    private fun lastIdea(ctx: Context): Answer {
        val n = NoteStore.all(ctx).firstOrNull {
            it.kind == "idea" && it.text != null && !it.failed && !it.text.startsWith("(")
        } ?: return Answer("آخر فكرة", "لسه مفيش أفكار متسجلة.")
        return Answer("آخر فكرة", "${n.text}\n${Fmt.dayLabel(n.id)}، ${Fmt.clock(n.id)}")
    }

    private fun reminders(ctx: Context, now: Long): Answer {
        val list = Reminders.upcoming(ctx).filter { it.first >= now - 60_000 }
        if (list.isEmpty()) return Answer("مفيش تذكيرات", "مفيش تذكيرات جاية.")
        val lines = list.take(6).map { "• ${Fmt.whenText(it.first)}\n   ${it.second}" }.toMutableList()
        if (list.size > 6) lines += "وكمان ${Fmt.num(list.size - 6)}"
        val title = if (list.size == 1) "تذكير واحد" else if (list.size == 2) "تذكيرين" else "${Fmt.num(list.size)} تذكيرات"
        return Answer(title, lines.joinToString("\n"))
    }
}
