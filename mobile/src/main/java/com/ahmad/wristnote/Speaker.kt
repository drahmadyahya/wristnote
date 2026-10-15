package com.ahmad.wristnote

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads Arabic text aloud with the device's text-to-speech engine. */
object Speaker {

    /**
     * Speaks [text] and blocks until done (call off the main thread).
     * Returns false when this device has no Arabic voice.
     */
    fun sayBlocking(ctx: Context, text: String, timeoutSec: Long = 40): Boolean {
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                var r = t.setLanguage(Locale("ar", "EG"))
                if (r < TextToSpeech.LANG_AVAILABLE) r = t.setLanguage(Locale("ar"))
                ok = r >= TextToSpeech.LANG_AVAILABLE
            }
            ready.countDown()
        }
        ready.await(10, TimeUnit.SECONDS)
        val t = tts ?: return false
        if (!ok) { t.shutdown(); return false }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finished.countDown() }
            @Deprecated("old api") override fun onError(id: String?) { finished.countDown() }
            override fun onError(id: String?, code: Int) { finished.countDown() }
        })
        t.setSpeechRate(1.0f)
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        t.speak(spoken(text), TextToSpeech.QUEUE_FLUSH, params, "nerdly")
        finished.await(timeoutSec, TimeUnit.SECONDS)
        t.shutdown()
        return true
    }

    fun sayAsync(ctx: Context, text: String, onNoArabic: () -> Unit = {}) {
        Thread {
            try { if (!sayBlocking(ctx, text)) onNoArabic() } catch (e: Exception) { Log.e("Nerdly", "tts", e) }
        }.start()
    }

    /**
     * Renders [text] to WAV bytes (for the watch to play), trying the default engine,
     * then Google and Samsung. Null when no Arabic voice exists. Blocking.
     */
    fun synthBytes(ctx: Context, text: String): ByteArray? {
        for (engine in listOf<String?>(null, "com.google.android.tts", "com.samsung.SMT")) {
            val b = try { synthWith(ctx.applicationContext, engine, spoken(text)) } catch (e: Exception) { null }
            if (b != null) return b
        }
        return null
    }

    private fun synthWith(ctx: Context, engine: String?, text: String): ByteArray? {
        val ready = CountDownLatch(1)
        var ok = false
        val init = TextToSpeech.OnInitListener { ok = it == TextToSpeech.SUCCESS; ready.countDown() }
        val tts = if (engine == null) TextToSpeech(ctx, init) else TextToSpeech(ctx, init, engine)
        try {
            if (!ready.await(6, TimeUnit.SECONDS) || !ok) return null
            val lang = listOf(Locale("ar", "EG"), Locale("ar", "SA"), Locale("ar"))
                .firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: return null
            if (tts.setLanguage(lang) < TextToSpeech.LANG_AVAILABLE) return null
            val out = java.io.File(ctx.cacheDir, "say_${System.nanoTime()}.wav")
            val done = CountDownLatch(1)
            var failed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("old api") override fun onError(id: String?) { failed = true; done.countDown() }
                override fun onError(id: String?, code: Int) { failed = true; done.countDown() }
            })
            if (tts.synthesizeToFile(text, Bundle(), out, "clip") != TextToSpeech.SUCCESS) return null
            val finished = done.await(20, TimeUnit.SECONDS)
            val bytes = if (finished && !failed && out.length() > 2000) out.readBytes() else null
            out.delete()
            return bytes
        } finally { tts.shutdown() }
    }

    /** Makes screen text read naturally: no bullets, times said the Egyptian way. */
    fun spoken(text: String): String {
        var s = text.replace("•", "").replace("←", "،")
        s = Regex("""([0-9٠-٩]{1,2})[:：]([0-9٠-٩]{2})\s*([مص])(?=$|[\s.،,])""").replace(s) { m ->
            val h = m.groupValues[1]
            val min = toInt(m.groupValues[2])
            val part = if (m.groupValues[3] == "م") "مساءً" else "صباحًا"
            val mins = when (min) { 0 -> ""; 15 -> " وربع"; 30 -> " ونص"; 45 -> " إلا ربع"; else -> " و${m.groupValues[2]} دقيقة" }
            val hour = if (min == 45) (toInt(h) % 12 + 1).toString() else h
            "الساعة $hour$mins $part"
        }
        return s.replace(Regex("\\n+"), "، ").replace(Regex("\\s+"), " ").trim()
    }

    private fun toInt(s: String) = s.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("").toIntOrNull() ?: 0
}
