package com.ahmad.wristnote

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

class TranscribeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getLong(KEY_ID, -1L)
        val note = NoteStore.get(ctx, id) ?: return Result.success()
        if (note.text != null) return Result.success()
        // No model yet: leave the note pending; the app re-queues it after download.
        if (!ModelManager.isReady(ctx)) {
            Notifier.waitingForModel(ctx)
            return Result.success()
        }
        return try {
            val text = WhisperEngine.transcribe(ModelManager.file(ctx), File(note.audio))
            val updated = note.copy(text = text.ifBlank { "(مسمعناش كلام واضح)" }, failed = false)
            NoteStore.upsert(ctx, updated)
            Notifier.newNote(ctx, updated)
            Result.success()
        } catch (e: Exception) {
            Log.e("WristNote", "transcription failed", e)
            NoteStore.upsert(ctx, note.copy(failed = true))
            Result.success()
        }
    }

    companion object {
        private const val KEY_ID = "id"

        fun enqueue(ctx: Context, id: Long) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            // One queue: whisper is CPU-heavy, so clips are processed in order.
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("transcribe", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun enqueuePending(ctx: Context) {
            NoteStore.all(ctx).filter { it.text == null }.sortedBy { it.id }
                .forEach { enqueue(ctx, it.id) }
        }
    }
}
