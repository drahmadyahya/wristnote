package com.ahmad.wristnote

import android.app.AlarmManager
import android.content.ContentValues
import android.provider.CalendarContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Deleting a command takes back what it did: calendar events, reminders, notebook items
 * and Clock alarms — for new notes (saved record) and older notes (rebuilt from the text).
 */
@RunWith(AndroidJUnit4::class)
class UndoTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val log = StringBuilder()
    private fun report(s: String) { Log.i("NerdlyTest", s); log.append(s).append('\n') }

    private fun shell(cmd: String) { inst.uiAutomation.executeShellCommand(cmd).close(); Thread.sleep(300) }

    /** Runs a sentence through the real pipeline (parser + actions) and returns the saved note. */
    private fun say(text: String): Note {
        val id = System.currentTimeMillis()
        val n = Note(id, text, "/dev/null")
        NoteStore.upsert(ctx, n)
        TranscribeWorker.process(ctx, n)
        Thread.sleep(50)
        return NoteStore.get(ctx, id)!!
    }

    private fun events(title: String): Int =
        ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DELETED}=0", arrayOf(title), null)?.use { it.count } ?: -1

    @Test
    fun deletingUndoesTheAction() {
        val pkg = ctx.packageName
        listOf("READ_CALENDAR", "WRITE_CALENDAR", "POST_NOTIFICATIONS").forEach { shell("pm grant $pkg android.permission.$it") }
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        ensureCalendar()
        try {
            // ── calendar event (new note: saved event id)
            val e = say("احفظ معاد التجربة يوم الخميس الساعة سبعة بالليل")
            assertEquals("event", e.kind)
            assertNotNull("event note should remember what it made", e.undo)
            val title = "معاد التجربة"
            report("EVENT made: ${events(title)} in calendar | delete asks: ${Undo.describe(ctx, e)}")
            assertEquals(1, events(title))
            report("EVENT undo: ${Undo.revert(ctx, e)} -> ${events(title)} left")
            assertEquals(0, events(title))

            // ── calendar event (older note without a record: found by title + time)
            val e2 = say("احفظ معاد التجربة التاني يوم السبت الساعة عشرة الصبح")
            NoteStore.upsert(ctx, e2.copy(undo = null))
            val old = NoteStore.get(ctx, e2.id)!!
            assertEquals(1, events("معاد التجربة التاني"))
            report("EVENT (old note) undo: ${Undo.revert(ctx, old)} -> ${events("معاد التجربة التاني")} left")
            assertEquals(0, events("معاد التجربة التاني"))

            // ── all-day event (no time)
            val e3 = say("سجل معاد التجربة التالت يوم الأحد")
            val t3 = NoteStore.get(ctx, e3.id)!!
            val made3 = ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE),
                "${CalendarContract.Events.ALL_DAY}=1 AND ${CalendarContract.Events.DELETED}=0", null, null)?.use { it.count } ?: 0
            report("ALLDAY made ($made3 all-day) summary=${t3.summary}")
            report("ALLDAY undo: ${Undo.revert(ctx, t3)}")
            val left3 = ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE),
                "${CalendarContract.Events.ALL_DAY}=1 AND ${CalendarContract.Events.DELETED}=0", null, null)?.use { it.count } ?: 0
            assertEquals(made3 - 1, left3)

            // ── reminder
            val r = say("فكرني كمان ساعتين أشرب مية")
            assertEquals("reminder", r.kind)
            val at = org.json.JSONObject(r.undo!!).getLong("at")
            assertTrue(Reminders.isArmed(ctx, at))
            assertTrue(Reminders.upcoming(ctx).any { it.first == at })
            report("REMINDER armed | delete asks: ${Undo.describe(ctx, r)} | undo: ${Undo.revert(ctx, r)}")
            assertFalse("reminder alarm should be cancelled", Reminders.isArmed(ctx, at))
            assertFalse(Reminders.upcoming(ctx).any { it.first == at })

            // ── reminder from an older note
            val r2 = say("فكرني كمان تلات ساعات أكلم سارة")
            val at2 = org.json.JSONObject(r2.undo!!).getLong("at")
            NoteStore.upsert(ctx, r2.copy(undo = null))
            report("REMINDER (old note) undo: ${Undo.revert(ctx, NoteStore.get(ctx, r2.id)!!)}")
            assertFalse(Reminders.isArmed(ctx, at2))

            // ── new notebook, then an add
            val c = say("اعملي نوتة اسمها التجربة وضيف جواها قلم وورقة")
            val nb = Notebooks.all(ctx).first { it.title == "التجربة" }
            assertEquals(2, nb.itemCount)
            val a = say("ضيف في نوتة التجربة كشكول")
            assertEquals(3, Notebooks.get(ctx, nb.id)!!.itemCount)
            report("NOTE add | delete asks: ${Undo.describe(ctx, a)} | undo: ${Undo.revert(ctx, a)}")
            assertEquals(listOf("قلم", "ورقة"), Notebooks.get(ctx, nb.id)!!.blocks.filter { !it.heading }.map { it.text })
            report("NOTE create | delete asks: ${Undo.describe(ctx, c)} | undo: ${Undo.revert(ctx, c)}")
            assertNull("notebook made by the command should go", Notebooks.get(ctx, nb.id))

            // ── answers/ideas: nothing to take back
            val q = say("فكرة جديدة نعمل كورس فارماكولوجي")
            assertNull(Undo.describe(ctx, q))

            // ── Clock alarm (phone's Clock app): the next system alarm should go away
            val am = ctx.getSystemService(AlarmManager::class.java)
            fun inMin(m: Int) = Calendar.getInstance().apply { add(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            for ((label, t) in listOf("in 50 min" to inMin(50), "in 5 h" to inMin(5 * 60 + 7))) {
                val res = Actions.run(ctx, Command.Alarm(t, ""))
                Thread.sleep(4000)
                val next = am.nextAlarmClock?.triggerTime
                report("ALARM $label (${Fmt.whenText(t)}) set ok=${res.ok} | system next alarm=${next?.let { Fmt.whenText(it) }}")
                val n = Note(System.currentTimeMillis(), "منبه", "/dev/null", kind = "alarm", summary = res.summary, undo = res.undo?.toString())
                report("  delete asks: ${Undo.describe(ctx, n)}")
                val msg = Undo.revert(ctx, n)
                Thread.sleep(4000)
                val after = am.nextAlarmClock?.triggerTime
                report("  undo: $msg -> system next alarm=${after?.let { Fmt.whenText(it) } ?: "none"} ${if (after != t) "OK (gone)" else "STILL SET"}")
                shell("input keyevent KEYCODE_HOME")
            }
            // left for the on-screen delete test in phone.sh
            val keep = say("احفظ معاد زيارة يوم الجمعة الساعة خمسة العصر")
            report("KEEP for UI test: ${keep.summary} (${events("معاد زيارة")} in calendar)")
            report("RESULT undo OK")
        } finally {
            File(ctx.getExternalFilesDir(null), "undo_report.txt").writeText(log.toString())
        }
    }

    private fun ensureCalendar() {
        val have = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { it.count } ?: 0
        if (have > 0) return
        val acct = "nerdly-test"
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, acct)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        ctx.contentResolver.insert(uri, ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, acct)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Test"); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Test")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, acct); put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1); put(CalendarContract.Calendars.IS_PRIMARY, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        })
    }
}
