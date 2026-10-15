package com.ahmad.wristnote

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline speech-to-text using whisper.cpp. One loaded model, one job at a time. */
object WhisperEngine {
    init { System.loadLibrary("wristnote") }

    private var ctx = 0L
    private var loadedPath: String? = null

    // Primes Whisper for Egyptian Arabic and for the command words we listen for,
    // so it spells them the way the command parser expects.
    private const val PROMPT =
        "كلام بالعامية المصرية. فكرني بكرة الساعة خمسة ونص. ظبطلي المنبه الساعة ستة الصبح. احفظ المعاد ده في التقويم. ضيف في نوتة الشغل تحت عنوان المهام."

    @Synchronized
    fun transcribe(model: File, wav: File, fast: Boolean, hints: List<String> = emptyList()): String {
        if (ctx == 0L || loadedPath != model.absolutePath) {
            if (ctx != 0L) nativeFree(ctx)
            ctx = nativeInit(model.absolutePath)
            loadedPath = model.absolutePath
            check(ctx != 0L) { "model failed to load" }
        }
        val samples = readWav(wav)
        // performance cores only (S24 Ultra: 1 X4 + 5 A720); the efficiency cores slow everyone down
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val seconds = samples.size / 16000.0
        // Measured on recorded Egyptian speech: the small model keeps the same text with a
        // 768 window (~15 s of audio) and runs ~2x faster; 512 starts making mistakes.
        // large-v3-turbo produces garbage with any trimmed window, so it always gets the full one.
        val audioCtx = if (fast) ((seconds * 50).toInt() + 128).coerceIn(768, 1500) else 0
        val maxTokens = (48 + seconds * 8).toInt().coerceAtMost(224)
        val raw = String(
            nativeTranscribe(ctx, samples, threads, prompt(hints).toByteArray(Charsets.UTF_8), audioCtx, maxTokens),
            Charsets.UTF_8
        )
        return clean(raw)
    }

    /** Adds the user's own notebook names so Whisper spells them the same way. */
    private fun prompt(hints: List<String>): String {
        val names = hints.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        return if (names.isEmpty()) PROMPT else "$PROMPT النوت: ${names.joinToString("، ")}."
    }

    /** True when the output is mostly Latin letters — Whisper went off the rails. */
    fun looksBroken(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return false
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' || it.code in 0x0400..0x04FF }
        return latin > letters.length * 0.4
    }

    /** Removes Whisper's well-known Arabic hallucinations and prompt echoes. */
    fun clean(text: String): String {
        var t = text.replace(Regex("""[\[(（][^\])）]{0,30}[\])）]"""), " ")
        val junk = listOf(
            "ترجمة نانسي قنقر", "نانسي قنقر", "اشتركوا في القناة", "اشترك في القناة",
            "شكرا للمشاهدة", "شكراً للمشاهدة", "شكرا على المشاهدة", "لا تنسوا الاشتراك",
            "كلام بالعامية المصرية", "موسيقى",
        )
        junk.forEach { t = t.replace(it, " ") }
        // collapse a sentence repeated back-to-back (a common decoder loop)
        val parts = t.split(Regex("(?<=[.!؟?،])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val dedup = parts.filterIndexed { i, s -> i == 0 || s != parts[i - 1] }
        return dedup.joinToString(" ").replace(Regex("\\s+"), " ").trim().trimEnd('.', '،')
    }

    /** 16 kHz mono 16-bit PCM WAV -> floats in [-1, 1]. Finds the data chunk. */
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        var offset = 12
        var dataStart = 44
        var dataLen = bytes.size - 44
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataStart = offset + 8
                dataLen = minOf(len, bytes.size - dataStart)
                break
            }
            offset += 8 + len
        }
        val shorts = ByteBuffer.wrap(bytes, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shorts.remaining())
        for (i in out.indices) out[i] = shorts.get(i) / 32768f
        return if (out.size < 16000) out.copyOf(16000) else out
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(ctx: Long)
    private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, prompt: ByteArray, audioCtx: Int, maxTokens: Int): ByteArray
}
