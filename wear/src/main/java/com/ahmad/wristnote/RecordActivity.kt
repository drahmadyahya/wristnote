package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opens straight into recording. Tap the big button to stop; the clip is handed
 * to the Wear Data Layer, which delivers it to the phone now or whenever the
 * phone reconnects.
 */
class RecordActivity : Activity() {

    private enum class State { IDLE, RECORDING, DONE }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var recorder: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var keepReading = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    private lateinit var timerText: TextView
    private lateinit var button: TextView
    private lateinit var hintText: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val sec = (System.currentTimeMillis() - startedAt) / 1000
            timerText.text = String.format("%d:%02d", sec / 60, sec % 60)
            if (sec >= MAX_SECONDS) stopAndSend() else handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            hintText.text = "اسمح بالمايك"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            hintText.text = "محتاج إذن المايك"
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        timerText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            text = "0:00"
        }
        val size = dp(96)
        button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E5484D"))
            }
            text = "■"
            setOnClickListener { onButton() }
        }
        hintText = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B0B0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            text = "بيسجّل… اضغط للإيقاف"
        }
        col.addView(timerText, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        col.addView(button, LinearLayout.LayoutParams(size, size))
        col.addView(hintText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun onButton() {
        when (state) {
            State.RECORDING -> stopAndSend()
            State.DONE -> finish()
            State.IDLE -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        }
    }

    @Suppress("MissingPermission")
    private fun startRecording() {
        if (state == State.RECORDING) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            hintText.text = "المايك مش متاح"; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); hintText.text = "المايك مش متاح"; return
        }
        pcm.reset()
        recorder = rec
        rec.startRecording()
        keepReading = true
        readThread = Thread {
            val buf = ByteArray(bufSize)
            while (keepReading) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.also { it.start() }
        state = State.RECORDING
        startedAt = System.currentTimeMillis()
        button.text = "■"
        hintText.text = "بيسجّل… اضغط للإيقاف"
        buzz(40)
        handler.post(tick)
    }

    private fun stopAndSend() {
        if (state != State.RECORDING) return
        state = State.DONE
        handler.removeCallbacks(tick)
        keepReading = false
        readThread?.join(1000)
        recorder?.run { try { stop() } catch (_: Exception) {}; release() }
        recorder = null
        buzz(80)

        val raw = synchronized(pcm) { pcm.toByteArray() }
        button.text = "✓"
        (button.background as GradientDrawable).setColor(Color.parseColor("#30A46C"))

        if (raw.size < SAMPLE_RATE) { // under half a second
            hintText.text = "قصير جدًا، اتلغى"
            handler.postDelayed({ finish() }, 1200)
            return
        }

        val now = System.currentTimeMillis()
        val req = PutDataMapRequest.create("$PATH_PREFIX/$now").apply {
            dataMap.putAsset("audio", Asset.createFromBytes(wav(raw)))
            dataMap.putLong("time", now)
        }.asPutDataRequest().setUrgent()

        hintText.text = "بيتبعت…"
        val app = applicationContext
        Wearable.getDataClient(app).putDataItem(req)
            .addOnSuccessListener {
                Wearable.getNodeClient(app).connectedNodes.addOnCompleteListener { t ->
                    val connected = t.isSuccessful && !t.result.isNullOrEmpty()
                    hintText.text = if (connected) "اتبعت للموبايل" else "اتحفظ، هيتبعت لما الموبايل يتوصل"
                    handler.postDelayed({ finish() }, 1500)
                }
            }
            .addOnFailureListener {
                hintText.text = "حصل خطأ في الإرسال"
                (button.background as GradientDrawable).setColor(Color.parseColor("#E5484D"))
                handler.postDelayed({ finish() }, 2500)
            }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app mid-recording (palm cover, swipe) still saves the note.
        if (state == State.RECORDING) stopAndSend()
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        const val MAX_SECONDS = 60
        const val PATH_PREFIX = "/wristnote/voice"
        private const val REQ_MIC = 1
    }
}
