package com.ahmad.wristnote

import android.content.ContentValues
import android.provider.CalendarContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * End-to-end on a real Android system: downloads the speech model through the
 * app's own downloader, transcribes recorded Egyptian-Arabic commands, runs
 * them through the parser and actions, and writes a report.
 */
@RunWith(AndroidJUnit4::class)
class DevicePipelineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val testCtx = inst.context
    private val log = StringBuilder()

    private fun report(s: String) { Log.i("NerdlyTest", s); log.append(s).append('\n') }

    private fun shell(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(300)
    }

    @Test
    fun fullPipeline() {
        val pkg = ctx.packageName
        listOf("READ_CALENDAR", "WRITE_CALENDAR", "POST_NOTIFICATIONS").forEach {
            shell("pm grant $pkg android.permission.$it")
        }
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        localCalendar()

        assertTrue("speech model missing from the APK", SpeechEngine.available(ctx))
        val phrases = testCtx.assets.open("speech/phrases.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.split("|") }
        var crashes = 0
        var matches = 0
        for ((name, spoken, expect) in phrases) {
            val wav = File(ctx.filesDir, "audio/$name.wav").apply { parentFile?.mkdirs() }
            testCtx.assets.open("speech/$name.wav").use { i -> wav.outputStream().use { i.copyTo(it) } }
            val id = System.currentTimeMillis()
            NoteStore.upsert(ctx, Note(id, null, wav.absolutePath))
            try {
                val start = System.currentTimeMillis()
                val hints = Notebooks.all(ctx).flatMap { nb -> listOf(nb.title) + nb.blocks.filter { it.heading }.map { it.text } }
                val text = SpeechEngine.transcribe(ctx, wav, hints)
                val secs = (System.currentTimeMillis() - start) / 1000.0
                TranscribeWorker.process(ctx, Note(id, text, wav.absolutePath, took = secs.toInt()))
                val n = NoteStore.get(ctx, id)!!
                if (n.kind == expect) matches++
                report("CASE $name | ${"%.1f".format(secs)}s | expect=$expect got=${n.kind} ${if (n.kind == expect) "OK" else "MISMATCH"}")
                report("  said : $spoken")
                report("  heard: $text")
                report("  did  : ${n.summary?.replace("\n", " / ") ?: "-"}")
            } catch (e: Throwable) {
                crashes++
                report("CASE $name | CRASH ${e.javaClass.simpleName}: ${e.message}")
                Log.e("NerdlyTest", "crash", e)
            }
            Thread.sleep(1100)
        }
        report("NOTEBOOKS " + Notebooks.all(ctx).joinToString { nb -> "${nb.title}=" + nb.blocks.joinToString("|") { (if (it.heading) "#" else "") + it.text } })
        report("REMINDERS " + Reminders.upcoming(ctx).joinToString { Fmt.whenText(it.first) + " " + it.second })
        report("RESULT matched $matches/${phrases.size}, crashes $crashes")
        File(ctx.getExternalFilesDir(null), "report.txt").writeText(log.toString())
        assertTrue("pipeline crashed $crashes times", crashes == 0)
    }

    /** A local calendar so "احفظ المعاد" has somewhere to write on a clean emulator. */
    private fun localCalendar() {
        val acct = "nerdly-test"
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, acct)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, acct)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Test")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFF0B429.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, acct)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.IS_PRIMARY, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        try { ctx.contentResolver.insert(uri, v) } catch (e: Exception) { report("calendar setup failed: ${e.message}") }
    }
}
