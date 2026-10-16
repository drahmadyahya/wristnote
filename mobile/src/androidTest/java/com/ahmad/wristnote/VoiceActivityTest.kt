package com.ahmad.wristnote

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone recorder, end to end: a recorded Egyptian clip is fed in place of the
 * microphone, the stop button saves it, and the screen must show the transcript and
 * what was done. Also checks that back discards. Screenshots go next to the report.
 */
@RunWith(AndroidJUnit4::class)
class VoiceActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val testCtx = inst.context
    private val log = StringBuilder()
    private val outDir get() = ctx.getExternalFilesDir(null)!!

    private fun report(s: String) { Log.i("NerdlyTest", s); log.append(s).append('\n') }

    private fun pcmOf(asset: String): ByteArray {
        val bytes = testCtx.assets.open("speech/$asset.wav").use { it.readBytes() }
        var off = 12
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(bytes, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return bytes.copyOfRange(off + 8, minOf(bytes.size, off + 8 + len))
            off += 8 + len
        }
        return bytes.copyOfRange(44, bytes.size)
    }

    private fun shot(name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun launch(): VoiceActivity {
        val i = Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return inst.startActivitySync(i) as VoiceActivity
    }

    private fun newestAfter(t: Long): Note? = NoteStore.all(ctx).filter { it.id >= t }.maxByOrNull { it.id }

    private fun recordAndCheck(asset: String, expectKind: String, shotPrefix: String?) {
        VoiceActivity.testAudio = pcmOf(asset)
        val t0 = System.currentTimeMillis()
        val act = launch()
        val clipMs = VoiceActivity.testAudio!!.size / 32L
        Thread.sleep(clipMs + 600)
        if (shotPrefix != null) shot("${shotPrefix}_1_recording")
        val pressed = System.currentTimeMillis()
        inst.runOnMainSync { act.save() }
        var n: Note? = null
        while (System.currentTimeMillis() - pressed < 30_000) {
            n = newestAfter(t0)
            if (n?.text != null) break
            Thread.sleep(100)
        }
        val secs = (System.currentTimeMillis() - pressed) / 1000.0
        assertNotNull("no note saved for $asset", n)
        Thread.sleep(900) // result animation
        if (shotPrefix != null) shot("${shotPrefix}_2_result")
        val visible = ArrayList<android.view.View>()
        inst.runOnMainSync { act.window.decorView.findViewsWithText(visible, n!!.text!!, android.view.View.FIND_VIEWS_WITH_TEXT) }
        report("VOICE $asset | ${"%.1f".format(secs)}s from stop to result | kind=${n!!.kind} expect=$expectKind ${if (n.kind == expectKind) "OK" else "MISMATCH"}")
        report("  heard: ${n.text}")
        report("  did  : ${n.summary?.replace("\n", " / ") ?: "-"}")
        report("  on screen: ${if (visible.isNotEmpty()) "yes" else "NO"}")
        assertTrue("transcript not shown on the record screen", visible.isNotEmpty())
        assertEquals("wrong command for $asset", expectKind, n.kind)
        inst.runOnMainSync { act.finish() }
        Thread.sleep(800)
    }

    @Test
    fun phoneRecorder() {
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").close()
        Thread.sleep(500)
        try {
            recordAndCheck("04_reminder", "reminder", "voice")
            recordAndCheck("09_idea", "idea", "voice_idea")
            recordAndCheck("06_event", "event", null)

            // back = discard: nothing saved
            VoiceActivity.testAudio = pcmOf("05_alarm")
            val t0 = System.currentTimeMillis()
            val act = launch()
            Thread.sleep(1500)
            inst.runOnMainSync { @Suppress("DEPRECATION") act.onBackPressed() }
            Thread.sleep(1500)
            val leaked = newestAfter(t0)
            report("DISCARD back pressed -> ${if (leaked == null) "nothing saved OK" else "SAVED (bug)"}")
            assertTrue("back should discard", leaked == null)
            report("RESULT voice recorder OK")
        } finally {
            VoiceActivity.testAudio = null
            File(outDir, "voice_report.txt").writeText(log.toString())
        }
    }
}
