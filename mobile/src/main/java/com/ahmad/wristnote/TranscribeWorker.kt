package com.ahmad.wristnote

import android.content.Context
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/** Speech -> text -> (command | idea). Runs one clip at a time. */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    /**
     * Runs as a foreground job so Android keeps it on the fast CPU cores;
     * in the background Samsung pins it to the slow ones.
     */
    override fun getForegroundInfo(): ForegroundInfo {
        val n = Notifier.working(applicationContext)
        return ForegroundInfo(WORKING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        try { setForegroundAsync(getForegroundInfo()).get() } catch (_: Exception) { /* not allowed now: run anyway */ }
        val id = inputData.getLong(KEY_ID, -1L)
        val note = NoteStore.get(ctx, id) ?: return Result.success()
        if (note.text != null) return Result.success()
        val model = ModelManager.usable(ctx)
        if (model == null) {
            Notifier.waitingForModel(ctx)
            return Result.success()
        }
        val hints = Notebooks.all(ctx).flatMap { nb -> listOf(nb.title) + nb.blocks.filter { it.heading }.map { it.text } }
        val started = System.currentTimeMillis()
        fun run(m: ModelManager.Model) =
            WhisperEngine.transcribe(ModelManager.file(ctx, m), File(note.audio), m == ModelManager.Model.FAST, hints)
        var text = try { run(model) } catch (e: Exception) {
            Log.e("Nerdly", "transcription failed", e); null
        }
        // accurate model went off the rails -> redo with the fast one
        if ((text == null || WhisperEngine.looksBroken(text)) && model != ModelManager.Model.FAST &&
            ModelManager.isReady(ctx, ModelManager.Model.FAST)) {
            text = try { run(ModelManager.Model.FAST) } catch (e: Exception) { null }
        }
        val took = ((System.currentTimeMillis() - started) / 1000).toInt()
        Log.i("Nerdly", "transcribed in ${took}s with $model")

        // another run already finished this note: don't overwrite it
        if (NoteStore.get(ctx, id)?.text != null) return Result.success()
        if (text == null || WhisperEngine.looksBroken(text)) {
            NoteStore.upsert(ctx, note.copy(failed = true, took = took))
            return Result.success()
        }
        if (text.isBlank()) {
            NoteStore.upsert(ctx, note.copy(text = "(مسمعناش كلام واضح)", failed = false, took = took))
            return Result.success()
        }
        process(ctx, note.copy(text = text, failed = false, took = took))
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val WORKING_ID = 7

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
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
