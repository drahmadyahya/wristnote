package com.ahmad.wristnote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Downloads the Whisper model once; after that everything runs offline. */
object ModelManager {
    private const val NAME = "ggml-small-q5_1.bin"
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"
    private const val PREFS = "model"
    private const val KEY_ID = "download_id"
    const val APPROX_MB = 190

    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), NAME)
    private fun part(ctx: Context) = File(ctx.getExternalFilesDir(null), "$NAME.part")

    fun isReady(ctx: Context) = file(ctx).let { it.exists() && it.length() > 100L * 1024 * 1024 }

    fun startDownload(ctx: Context) {
        if (isReady(ctx) || downloadId(ctx) != -1L) return
        part(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("فكرتي: ملف التحويل لنص")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part(ctx)))
        val id = dm(ctx).enqueue(req)
        prefs(ctx).edit().putLong(KEY_ID, id).apply()
    }

    /** Returns progress 0..100, 100 = ready, -1 = not started, -2 = failed. */
    fun poll(ctx: Context): Int {
        if (isReady(ctx)) return 100
        val id = downloadId(ctx)
        if (id == -1L) return -1
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { clear(ctx); return -1 }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    clear(ctx)
                    if (part(ctx).renameTo(file(ctx))) 100 else -2
                }
                DownloadManager.STATUS_FAILED -> { clear(ctx); -2 }
                else -> if (total > 0) ((done * 99) / total).toInt() else 0
            }
        }
    }

    private fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY_ID).apply()
    private fun downloadId(ctx: Context) = prefs(ctx).getLong(KEY_ID, -1L)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)
}
