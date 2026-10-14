package com.ahmad.wristnote

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline speech-to-text using whisper.cpp. One loaded model, one job at a time. */
object WhisperEngine {
    init { System.loadLibrary("wristnote") }

    private var ctx = 0L

    @Synchronized
    fun transcribe(model: File, wav: File): String {
        if (ctx == 0L) {
            ctx = nativeInit(model.absolutePath)
            check(ctx != 0L) { "model failed to load" }
        }
        val samples = readWav(wav)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return String(nativeTranscribe(ctx, samples, threads), Charsets.UTF_8).trim()
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
        // whisper needs at least ~1s; pad short clips with silence
        return if (out.size < 16000) out.copyOf(16000) else out
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int): ByteArray
}
