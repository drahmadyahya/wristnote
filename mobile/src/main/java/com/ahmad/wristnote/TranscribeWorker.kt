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

/** Speech -> text -> (command | idea). Runs one clip at a time. */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getLong(KEY_ID, -1L)
        val note = NoteStore.get(ctx, id) ?: return Result.success()
        if (note.text != null) return Result.success()
        val model = ModelManager.usable(ctx)
        if (model == null) {
            Notifier.waitingForModel(ctx)
            return Result.success()
        }
        val text = try {
            WhisperEngine.transcribe(ModelManager.file(ctx, model), File(note.audio), model == ModelManager.Model.FAST,
                Notebooks.all(ctx).flatMap { nb -> listOf(nb.title) + nb.blocks.filter { it.heading }.map { it.text } })
        } catch (e: Exception) {
            Log.e("Nerdly", "transcription failed", e)
            NoteStore.upsert(ctx, note.copy(failed = true))
            return Result.success()
        }
        if (text.isBlank()) {
            NoteStore.upsert(ctx, note.copy(text = "(مسمعناش كلام واضح)", failed = false))
            return Result.success()
        }
        process(ctx, note.copy(text = text, failed = false))
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"

        /** Turns the text into a command if it is one, otherwise keeps it as an idea. */
        fun process(ctx: Context, note: Note) {
            val cmd = try {
                CommandParser.parse(note.text ?: "", Notebooks.all(ctx).map { it.ref() }, System.currentTimeMillis())
            } catch (e: Exception) { Log.e("Nerdly", "parse failed", e); null }
            if (cmd == null) {
                val n = note.copy(kind = "idea", summary = null)
                NoteStore.upsert(ctx, n)
                Notifier.newIdea(ctx, n)
                return
            }
            val r = try { Actions.run(ctx, cmd) } catch (e: Exception) {
                Log.e("Nerdly", "action failed", e); Actions.Result("idea", "مقدرناش ننفّذ الأمر", false)
            }
            val n = note.copy(kind = r.kind, summary = r.summary)
            NoteStore.upsert(ctx, n)
            Notifier.commandDone(ctx, n, r.ok)
        }

        fun enqueue(ctx: Context, id: Long) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("transcribe", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun enqueuePending(ctx: Context) {
            NoteStore.all(ctx).filter { it.text == null && !it.failed }.sortedBy { it.id }
                .forEach { enqueue(ctx, it.id) }
        }
    }
}
