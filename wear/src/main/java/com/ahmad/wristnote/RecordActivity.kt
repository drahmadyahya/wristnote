package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Opens straight into recording.
 *  - tap anywhere: save and send to the phone
 *  - swipe right (system back): discard
 *  - screen off / palm cover: save
 */
class RecordActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: RecordView
    private var recorder: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var keepReading = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L
    private var recording = false
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            view.elapsedMs = System.currentTimeMillis() - startedAt
            view.invalidate()
            if (view.elapsedMs >= MAX_MS) stop(save = true) else handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = RecordView(this).apply {
            maxMs = MAX_MS
            setOnClickListener { onTap() }
        }
        setContentView(view)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else { done = true; view.show(RecordView.Mode.NEED_MIC, "محتاج إذن المايك\nالمس عشان تسمح") }
    }

    private fun onTap() {
        when {
            recording -> stop(save = true)
            view.mode == RecordView.Mode.NEED_MIC ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            done -> finish()
        }
    }

    @Suppress("MissingPermission")
    private fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = SAMPLE_RATE / 20 * 2 // 50 ms of 16-bit audio
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            done = true
            view.show(RecordView.Mode.ERROR, "المايك مش متاح\nجرّب تاني")
            return
        }
        pcm.reset()
        recorder = rec
        rec.startRecording()
        keepReading = true
        readThread = Thread {
            val buf = ByteArray(chunk)
            val sb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            while (keepReading) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(pcm) { pcm.write(buf, 0, n) }
                var sum = 0.0
                for (i in 0 until n / 2) { val s = sb.getShort(i * 2) / 32768.0; sum += s * s }
                val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
                // map roughly -50 dB..-10 dB to 0..1
                val db = 20 * log10(rms.coerceAtLeast(1e-5))
                view.pushLevel(((db + 50) / 40).toFloat())
            }
        }.also { it.start() }
        recording = true
        startedAt = System.currentTimeMillis()
        buzz(30)
        handler.post(tick)
    }

    private fun stop(save: Boolean) {
        if (!recording) return
        recording = false
        done = true
        handler.removeCallbacks(tick)
        keepReading = false
        readThread?.join(500)
        recorder?.run { try { stop() } catch (_: Exception) {}; release() }
        recorder = null
        if (!save) return

        val raw = synchronized(pcm) { pcm.toByteArray() }
        if (raw.size < SAMPLE_RATE) { // < 0.5 s
            view.show(RecordView.Mode.SHORT, "قصيرة جدًا\nمتحفظتش")
            handler.postDelayed({ finish() }, 1400)
            return
        }
        buzz(60)
        send(raw)
    }

    private fun send(raw: ByteArray) {
        val now = System.currentTimeMillis()
        val req = PutDataMapRequest.create("$PATH_PREFIX/$now").apply {
            dataMap.putAsset("audio", Asset.createFromBytes(wav(raw)))
            dataMap.putLong("time", now)
        }.asPutDataRequest().setUrgent()

        val app = applicationContext
        val ui = !isFinishing
        Wearable.getDataClient(app).putDataItem(req)
            .addOnSuccessListener {
                if (!ui) return@addOnSuccessListener
                Wearable.getNodeClient(app).connectedNodes.addOnCompleteListener { t ->
                    val connected = t.isSuccessful && !t.result.isNullOrEmpty()
                    if (connected) view.show(RecordView.Mode.SENT, "اتبعت")
                    else view.show(RecordView.Mode.QUEUED, "اتحفظت\nهتتبعت لما الموبايل يتوصل")
                    handler.postDelayed({ finish() }, if (connected) 1100 else 2000)
                }
            }
            .addOnFailureListener {
                if (!ui) return@addOnFailureListener
                view.show(RecordView.Mode.ERROR, "متبعتتش\nجرّب تاني")
                handler.postDelayed({ finish() }, 2200)
            }
    }

    override fun onStop() {
        super.onStop()
        // Swipe-to-dismiss finishes the activity -> discard.
        // Screen off / palm cover just stops it -> keep the thought.
        if (recording) stop(save = !isFinishing)
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
        const val PATH_PREFIX = "/wristnote/voice"
        private const val REQ_MIC = 1
    }
}
