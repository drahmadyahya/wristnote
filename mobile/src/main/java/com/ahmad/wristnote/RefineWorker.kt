package com.ahmad.wristnote

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/** Re-writes a plain idea with the accurate model, after the fast pass already saved it. */
class RefineWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun getForegroundInfo() =
        ForegroundInfo(8, Notifier.working(applicationContext, "بحسّن كتابة الفكرة…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getLong("id", -1L)
        val note = NoteStore.get(ctx, id) ?: return Result.success()
        if (note.kind != "idea" || note.refined || !ModelManager.isReady(ctx, ModelManager.Model.ACCURATE)) return Result.success()
        try { setForegroundAsync(getForegroundInfo()).get() } catch (_: Exception) {}
        val hints = Notebooks.all(ctx).flatMap { nb -> listOf(nb.title) + nb.blocks.filter { it.heading }.map { it.text } }
        val text = try {
            WhisperEngine.transcribe(ModelManager.file(ctx, ModelManager.Model.ACCURATE), File(note.audio), false, hints)
        } catch (e: Exception) { Log.e("Nerdly", "refine failed", e); null }
        // the note may have changed while we worked (deleted, turned into a command, edited)
        val now = NoteStore.get(ctx, id) ?: return Result.success()
        if (now.kind != "idea") return Result.success()
        if (text.isNullOrBlank() || WhisperEngine.looksBroken(text)) {
            NoteStore.upsert(ctx, now.copy(refined = true))
        } else {
            NoteStore.upsert(ctx, now.copy(text = text, refined = true))
        }
        return Result.success()
    }

    companion object {
        fun enqueue(ctx: Context, id: Long) {
            val req = OneTimeWorkRequestBuilder<RefineWorker>().setInputData(workDataOf("id" to id)).build()
            // separate queue: never holds up new commands
            WorkManager.getInstance(ctx).enqueueUniqueWork("refine", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
