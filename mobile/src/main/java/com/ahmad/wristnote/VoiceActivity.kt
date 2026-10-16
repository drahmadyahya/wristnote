package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Record on the phone when the watch isn't around: the same dial as the watch,
 * a big stop button within thumb reach, and the result right on this screen.
 *  - stop button: save, transcribe, show what was understood
 *  - back / ✕: discard
 *  - screen off: save (like the watch)
 */
class VoiceActivity : Activity() {

    private enum class State { RECORDING, WORKING, RESULT, IDLE }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var dial: DialView
    private lateinit var mainBtn: FrameLayout
    private lateinit var stopMark: View
    private lateinit var underBtn: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var resultChip: LinearLayout
    private lateinit var actions: LinearLayout

    private var recorder: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var keepReading = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L
    private var state = State.IDLE
    private var noteId = 0L
    private var savedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            dial.elapsedMs = System.currentTimeMillis() - startedAt
            dial.invalidate()
            if (dial.elapsedMs >= MAX_MS) save() else handler.postDelayed(this, 100)
        }
    }

    private val watcher = object : Runnable {
        override fun run() {
            if (state != State.WORKING) return
            val n = NoteStore.get(this@VoiceActivity, noteId)
            when {
                n == null -> close()
                n.text != null -> showResult(n)
                n.failed -> showFailed()
                else -> {
                    if (System.currentTimeMillis() - savedAt > 20_000) dial.caption("لسه بتتحوّل…\nهتلاقيها في الأفكار")
                    handler.postDelayed(this, 120)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        build()
        begin()
    }

    // ───────────── layout ─────────────
    private fun build() {
        val amber = Color.parseColor("#F2BE3C")
        val white = Color.parseColor("#F3F0E8")
        val muted = Color.parseColor("#9C978C")
        val w = resources.displayMetrics.widthPixels

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // top: ✕ ……… logo
        val close = ui.iconButton(R.drawable.ic_close, white, "إلغاء") { discard() }
        val logo = android.widget.ImageView(this).apply { setImageResource(R.drawable.logo) }
        val top = ui.row(close, ui.spacer(), logo).apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(20), 0) }
        logo.layoutParams = LinearLayout.LayoutParams(ui.dp(32), ui.dp(32))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // the watch dial
        dial = DialView(this).apply {
            maxMs = MAX_MS
            face = Color.parseColor("#0E0D0A")
            hint = ""
        }
        val size = (w * 0.86f).toInt()
        col.addView(dial, LinearLayout.LayoutParams(size, size).apply { topMargin = ui.dp(28) })

        // result (hidden while recording)
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(ui.dp(28), 0, ui.dp(28), 0)
        }
        resultText = ui.text(20f, ui.medium, white, lineMult = 1.45f).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_ANY_RTL
            maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        resultBox.addView(resultText, LinearLayout.LayoutParams(-1, -2))
        resultChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#2A2210")); cornerRadius = ui.dp(18f) }
        }
        resultBox.addView(resultChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(16) })
        col.addView(resultBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        // bottom: the big stop button (thumb zone)
        mainBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(amber) }
            elevation = ui.dp(6f)
            contentDescription = "حفظ"
            isClickable = true; isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) })
            setOnClickListener { save() }
        }
        stopMark = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#1E1B16")); cornerRadius = ui.dp(7f) }
        }
        mainBtn.addView(stopMark, FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER))
        col.addView(mainBtn, LinearLayout.LayoutParams(ui.dp(88), ui.dp(88)))
        underBtn = ui.text(14f, color = muted).apply { text = "اضغط عشان تحفظ"; gravity = Gravity.CENTER }
        col.addView(underBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(14) })

        // after the result: record again / done
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }
        val again = pill("سجّل تاني", filled = false) { recordAgain() }
        val done = pill("تمام", filled = true) { close() }
        actions.addView(again, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        actions.addView(done, LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply { marginStart = ui.dp(12) })
        col.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(col) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.setPadding(b.left, b.top, b.right, b.bottom + ui.dp(36))
            insets
        }
        setContentView(root)
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        typeface = ui.medium
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.parseColor("#1E1B16") else Color.parseColor("#F2BE3C"))
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(28f)
            if (filled) setColor(Color.parseColor("#F2BE3C"))
            else { setColor(Color.TRANSPARENT); setStroke(ui.dp(2), Color.parseColor("#F2BE3C")) }
        }
        setOnClickListener { onClick() }
    }

    // ───────────── recording ─────────────
    private fun begin() {
        if (testAudio == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_MIC) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else needMic()
    }

    private fun needMic() {
        state = State.IDLE
        dial.show(DialView.Mode.NEED_MIC, "محتاج إذن المايك")
        stopMark.visibility = View.GONE
        underBtn.text = "اضغط عشان تسمح بالمايك"
        mainBtn.contentDescription = "اسمح بالمايك"
        mainBtn.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        // came back from the app settings with the mic allowed
        if (state == State.IDLE && dial.mode == DialView.Mode.NEED_MIC &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recordAgain()
    }

    @Suppress("MissingPermission")
    private fun start() {
        if (state == State.RECORDING) return
        val chunk = SAMPLE_RATE / 20 * 2 // 50 ms of 16-bit audio
        val fake = testAudio
        var rec: AudioRecord? = null
        if (fake == null) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                failMic(); return
            }
            try { rec.startRecording() } catch (e: Exception) { rec.release(); failMic(); return }
        }
        synchronized(pcm) { pcm.reset() }
        recorder = rec
        keepReading = true
        readThread = Thread {
            val buf = ByteArray(chunk)
            val sb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (keepReading) {
                val n = if (fake != null) {
                    // test input: feed the clip at real-time pace, then silence
                    Thread.sleep(50)
                    java.util.Arrays.fill(buf, 0)
                    val take = minOf(buf.size, fake.size - pos)
                    if (take > 0) { System.arraycopy(fake, pos, buf, 0, take); pos += take }
                    buf.size
                } else rec!!.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(pcm) { pcm.write(buf, 0, n) }
                var sum = 0.0
                for (i in 0 until n / 2) { val s = sb.getShort(i * 2) / 32768.0; sum += s * s }
                val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
                val db = 20 * log10(rms.coerceAtLeast(1e-5))
                dial.pushLevel(((db + 50) / 40).toFloat())
            }
        }.also { it.start() }
        state = State.RECORDING
        startedAt = System.currentTimeMillis()
        dial.elapsedMs = 0
        dial.show(DialView.Mode.RECORDING, "")
        showRecordingControls()
        buzz(30)
        handler.post(tick)
    }

    private fun failMic() {
        state = State.IDLE
        dial.show(DialView.Mode.ERROR, "المايك مش متاح\nممكن تطبيق تاني ماسكه")
        stopMark.visibility = View.GONE
        underBtn.text = "اضغط عشان تجرّب تاني"
        mainBtn.setOnClickListener { recordAgain() }
    }

    private fun stopMic() {
        handler.removeCallbacks(tick)
        keepReading = false
        readThread?.join(500)
        recorder?.run { try { stop() } catch (_: Exception) {}; release() }
        recorder = null
    }

    /** Stop button (or the 60 s limit, or the screen going off). */
    fun save() {
        if (state != State.RECORDING) return
        state = State.WORKING
        stopMic()
        val raw = synchronized(pcm) { pcm.toByteArray() }
        if (raw.size < SAMPLE_RATE) { // < 0.5 s
            state = State.IDLE
            dial.show(DialView.Mode.SHORT, "قصيرة جدًا\nمتحفظتش")
            showActions()
            return
        }
        buzz(60)
        noteId = System.currentTimeMillis()
        savedAt = noteId
        val dir = File(filesDir, "audio").apply { mkdirs() }
        val out = File(dir, "$noteId.wav")
        out.writeBytes(wav(raw))
        NoteStore.upsert(this, Note(noteId, null, out.absolutePath))
        TranscribeWorker.enqueue(this, noteId)

        dial.show(DialView.Mode.SENT, "اتحفظت\nبتتحوّل لنص…")
        mainBtn.visibility = View.INVISIBLE
        underBtn.visibility = View.INVISIBLE
        handler.post(watcher)
    }

    private fun showResult(n: Note) {
        state = State.RESULT
        val idea = n.kind == "idea"
        dial.caption(if (idea) "اتحفظت كفكرة" else "اتنفّذ")
        resultText.text = n.text
        resultChip.removeAllViews()
        val (icon, label) = when (n.kind) {
            "alarm" -> R.drawable.ic_alarm to (n.summary ?: "اتظبط المنبه")
            "reminder" -> R.drawable.ic_bell to (n.summary ?: "هفكّرك")
            "event" -> R.drawable.ic_event to (n.summary ?: "اتسجل في التقويم")
            "note" -> R.drawable.ic_note to (n.summary ?: "اتضافت في النوتة")
            "answer" -> R.drawable.ic_answer to (n.summary ?: "")
            else -> R.drawable.ic_bulb to "في الأفكار"
        }
        resultChip.addView(ui.icon(icon, Color.parseColor("#F2BE3C"), 20))
        resultChip.addView(ui.text(15f, ui.medium, Color.parseColor("#F3F0E8"), lineMult = 1.4f).apply {
            text = label; maxLines = 8; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })
        resultBox.visibility = View.VISIBLE
        resultBox.alpha = 0f; resultBox.translationY = ui.dp(12f)
        resultBox.animate().alpha(1f).translationY(0f).setDuration(260).start()
        showActions()
    }

    private fun showFailed() {
        state = State.RESULT
        dial.show(DialView.Mode.ERROR, "مقدرناش نحوّلها لنص\nهتلاقيها في الأفكار")
        showActions()
    }

    private fun showRecordingControls() {
        resultBox.visibility = View.GONE
        actions.visibility = View.GONE
        mainBtn.visibility = View.VISIBLE
        underBtn.visibility = View.VISIBLE
        stopMark.visibility = View.VISIBLE
        underBtn.text = "اضغط عشان تحفظ"
        mainBtn.contentDescription = "حفظ"
        mainBtn.setOnClickListener { save() }
    }

    private fun showActions() {
        mainBtn.visibility = View.GONE
        underBtn.visibility = View.GONE
        actions.visibility = View.VISIBLE
        actions.alpha = 0f
        actions.animate().alpha(1f).setDuration(200).start()
    }

    private fun recordAgain() {
        handler.removeCallbacks(watcher)
        state = State.IDLE
        begin()
    }

    private fun discard() {
        if (state == State.RECORDING) { state = State.IDLE; stopMic() }
        close()
    }

    private fun close() {
        handler.removeCallbacks(watcher)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { discard() }

    override fun onStop() {
        super.onStop()
        // screen off / switched away mid-thought: keep it, like the watch
        if (state == State.RECORDING && !isFinishing) save()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (state == State.RECORDING) stopMic()
        super.onDestroy()
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun wav(pcmData: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmData.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcmData.size)
        }.array()
        return header + pcmData
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_MS = 60_000L
        private const val REQ_MIC = 5

        /** Device tests feed a recorded clip instead of the microphone (16-bit PCM bytes). */
        @Volatile var testAudio: ByteArray? = null

        fun intent(ctx: Context) = Intent(ctx, VoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
