package com.ahmad.wristnote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Two speech models, downloaded once each on demand:
 *  - FAST: small. Every recording goes through it first (seconds).
 *  - ACCURATE: large-v3-turbo. Too slow for commands on a phone (~1 min),
 *    so when chosen it only re-writes plain ideas in the background.
 */
object ModelManager {
    enum class Model(val file: String, val mb: Int, val minBytes: Long) {
        ACCURATE("ggml-large-v3-turbo-q5_0.bin", 550, 400L * 1024 * 1024),
        FAST("ggml-small-q5_1.bin", 190, 100L * 1024 * 1024);
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    }

    private const val PREFS = "model"

    /** Only the fast model is used: on a phone the large one was far too slow, even in the background. */
    fun selected(ctx: Context): Model = Model.FAST

    fun select(ctx: Context, m: Model) = prefs(ctx).edit().putString("selected", m.name).apply()

    fun file(ctx: Context, m: Model = selected(ctx)) = File(ctx.getExternalFilesDir(null), m.file)
    private fun part(ctx: Context, m: Model) = File(ctx.getExternalFilesDir(null), "${m.file}.part")

    fun isReady(ctx: Context, m: Model = selected(ctx)) = file(ctx, m).let { it.exists() && it.length() > m.minBytes }

    /** The model for the first pass: the fast one whenever it is there. */
    fun usable(ctx: Context): Model? = if (isReady(ctx, Model.FAST)) Model.FAST else null

    /** Ideas get a second, more accurate pass in the background. */
    fun refineIdeas(ctx: Context) = false

    /** Removes the large model (550 MB) and any background re-writes left from older versions. */
    fun dropAccurate(ctx: Context) {
        try { androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("refine") } catch (_: Exception) {}
        val id = downloadId(ctx, Model.ACCURATE)
        if (id != -1L) { try { dm(ctx).remove(id) } catch (_: Exception) {}; clear(ctx, Model.ACCURATE) }
        file(ctx, Model.ACCURATE).delete(); part(ctx, Model.ACCURATE).delete()
    }

    fun startDownload(ctx: Context, m: Model = selected(ctx)) {
        if (isReady(ctx, m) || downloadId(ctx, m) != -1L) return
        part(ctx, m).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("Nerdly Task: ملف التحويل لنص")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part(ctx, m)))
        val id = dm(ctx).enqueue(req)
        prefs(ctx).edit().putLong("dl_${m.name}", id).apply()
    }

    /** 0..99 downloading, 100 ready, -1 not started, -2 failed. */
    fun poll(ctx: Context, m: Model = selected(ctx)): Int {
        if (isReady(ctx, m)) return 100
        val id = downloadId(ctx, m)
        if (id == -1L) return -1
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { clear(ctx, m); return -1 }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    clear(ctx, m)
                    if (part(ctx, m).renameTo(file(ctx, m))) 100 else -2
                }
                DownloadManager.STATUS_FAILED -> { clear(ctx, m); -2 }
                else -> if (total > 0) ((done * 99) / total).toInt() else 0
            }
        }
    }

    private fun clear(ctx: Context, m: Model) = prefs(ctx).edit().remove("dl_${m.name}").apply()
    private fun downloadId(ctx: Context, m: Model) = prefs(ctx).getLong("dl_${m.name}", -1L)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)
}
