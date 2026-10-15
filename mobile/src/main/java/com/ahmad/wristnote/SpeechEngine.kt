package com.ahmad.wristnote

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech -> text with Moonshine's Arabic model (tiny streaming, ~32 MB, ships inside the app).
 * Trained with Egyptian speech; in our tests ~30x faster than Whisper small and more accurate.
 */
object SpeechEngine {
    private const val ASSET_DIR = "moonshine-ar"
    private const val VERSION = "26_08_24"

    /** Command words and names the decoder should prefer when unsure. */
    private val BASE_TERMS = listOf(
        "فكرني", "ظبطلي", "صحيني", "المنبه", "نوتة", "ضيف", "اعملي", "معاد", "احفظ", "سجل",
        "التذكيرات", "ملخص", "النهارده", "بكرة", "ميدكينج", "ميديو",
    )

    private var tr: Transcriber? = null

    fun available(ctx: Context) = try { ctx.assets.list(ASSET_DIR)?.isNotEmpty() == true } catch (_: Exception) { false }

    @Synchronized
    fun transcribe(ctx: Context, wav: File, hints: List<String> = emptyList()): String {
        val t = tr ?: load(ctx).also { tr = it }
        val terms = (BASE_TERMS + hints.map { it.replace(",", " ").replace("،", " ").trim() })
            .filter { it.length in 2..30 }.distinct().take(40)
        try { t.setKeyterms(terms) } catch (e: Exception) { Log.w("Nerdly", "keyterms", e) }
        val res = t.transcribeWithoutStreaming(readWav(wav), 16000)
        val text = res.lines.orEmpty().joinToString(" ") { it.text.orEmpty().trim() }
        return clean(text)
    }

    private fun load(ctx: Context): Transcriber {
        val dir = File(ctx.filesDir, "$ASSET_DIR-$VERSION")
        val files = ctx.assets.list(ASSET_DIR).orEmpty()
        check(files.isNotEmpty()) { "speech model missing from the app" }
        if (files.any { !File(dir, it).exists() }) {
            dir.mkdirs()
            for (f in files) {
                val tmp = File(dir, "$f.tmp")
                ctx.assets.open("$ASSET_DIR/$f").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(File(dir, f))
            }
            // older copies
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("$ASSET_DIR-") && it != dir }?.forEach { it.deleteRecursively() }
        }
        val t = Transcriber(listOf(TranscriberOption("max_tokens_per_second", "13.0")))
        t.loadFromFiles(dir.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING)
        return t
    }

    /** Drops vowel marks and decoder loops so the text reads cleanly. */
    fun clean(text: String): String {
        var t = text.replace(Regex("[ً-ْٰ]"), "")
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // the same word 4+ times in a row is a loop, not speech
        val out = ArrayList<String>()
        for (w in words) {
            if (out.size >= 3 && out.takeLast(3).all { it == w }) continue
            out.add(w)
        }
        t = out.joinToString(" ")
        return t.trim().trimEnd('.', '،')
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
        return out
    }
}
