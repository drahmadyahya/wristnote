package com.ahmad.wristnote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.LinearLayout
import android.widget.Toast

/** Bottom sheet for one captured thought. */
object NoteSheet {
    fun show(activity: Activity, ui: Ui, note: Note) {
        val s = Sheet(activity, ui)
        s.body.addView(ui.text(13f, color = ui.muted).apply {
            text = "${Fmt.dayLabel(note.id)}، ${Fmt.clock(note.id)}" +
                if (note.took > 0) "، اتكتبت في ${Fmt.num(note.took)} ثانية" else ""
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
        })
        s.body.addView(ui.text(20f, lineMult = 1.5f).apply {
            text = note.text ?: if (note.failed) "مقدرناش نحوّلها لنص." else "بتتحوّل لنص…"
            if (note.text == null) setTextColor(ui.muted)
            setTextIsSelectable(note.text != null)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10))
        })
        if (note.summary != null) {
            val k = ui.kindIcon(note.kind)
            val chip = ui.row().apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(14), ui.dp(8))
                background = ui.rounded(ui.accentSoft, ui.dp(14f))
                if (k != 0) addView(ui.icon(k, ui.accentText, 18))
                addView(ui.text(14f, ui.medium, ui.ink).apply { text = note.summary },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
            }
            s.body.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12)) })
        }
        s.divider()

        val text = note.text
        if (text != null) {
            s.action(R.drawable.ic_retry, "افهم الأمر تاني") {
                // take back what it did the first time, so nothing is done twice
                Thread { Undo.revert(activity, note); TranscribeWorker.process(activity.applicationContext, note.copy(undo = null)) }.start()
                Toast.makeText(activity, "بنفّذه تاني…", Toast.LENGTH_SHORT).show()
            }
            if (note.kind != "idea") {
                s.action(R.drawable.ic_note, "اعتبرها فكرة عادية") {
                    Thread {
                        val done = Undo.revert(activity, note)
                        NoteStore.upsert(activity, note.copy(kind = "idea", summary = null, undo = null))
                        if (done != null) activity.runOnUiThread { Toast.makeText(activity, done, Toast.LENGTH_SHORT).show() }
                    }.start()
                }
            }
            if (note.kind == "idea" && Notebooks.all(activity).isNotEmpty()) {
                s.action(R.drawable.ic_list, "ضيفها لنوتة") { pickNotebook(activity, ui, note, text) }
            }
            for ((label, pkg) in Share.targets(activity)) {
                val icon = if (pkg == Share.KEEP) R.drawable.ic_bulb else R.drawable.ic_note
                s.action(icon, "حفظ في $label") { activity.startActivity(Share.intent(text, pkg)) }
            }
            s.action(R.drawable.ic_copy, "نسخ النص") {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("فكرة", text))
                Toast.makeText(activity, "اتنسخ", Toast.LENGTH_SHORT).show()
            }
            s.action(R.drawable.ic_share, "مشاركة") {
                activity.startActivity(Intent.createChooser(Share.intent(text, null), null))
            }
        } else if (note.failed) {
            s.action(R.drawable.ic_retry, "حاول تاني") {
                NoteStore.upsert(activity, note.copy(failed = false))
                TranscribeWorker.enqueue(activity, note.id)
            }
        }
        if (note.text != null && java.io.File(note.audio).exists()) {
            s.action(R.drawable.ic_retry, "اكتبها تاني من الصوت") {
                NoteStore.upsert(activity, note.copy(text = null, failed = false, kind = "idea", summary = null, took = 0))
                TranscribeWorker.enqueue(activity, note.id)
            }
        }
        s.action(R.drawable.ic_delete, "حذف", ui.danger) { confirmDelete(activity, ui, note) }
        s.show()
    }

    /** Manual fallback when the voice command wasn't recognised. */
    private fun pickNotebook(activity: Activity, ui: Ui, note: Note, text: String) {
        val s = Sheet(activity, ui)
        s.title("تتضاف فين؟")
        for (nb in Notebooks.all(activity)) {
            s.action(R.drawable.ic_list, nb.title) {
                val headings = nb.blocks.filter { it.heading }
                if (headings.isEmpty()) add(activity, note, nb.id, null, text)
                else {
                    val h = Sheet(activity, ui)
                    h.title("تحت أنهي عنوان في «${nb.title}»؟")
                    headings.forEach { b -> h.action(R.drawable.ic_add, b.text) { add(activity, note, nb.id, b.id, text) } }
                    h.action(R.drawable.ic_add, "في آخر النوتة") { add(activity, note, nb.id, null, text) }
                    h.show()
                }
            }
        }
        s.show()
    }

    private fun add(activity: Activity, note: Note, nbId: Long, anchor: Long?, text: String) {
        val nb = Notebooks.addItem(activity, nbId, anchor, text) ?: return
        val heading = anchor?.let { id -> nb.blocks.firstOrNull { it.id == id }?.text }
        NoteStore.upsert(activity, note.copy(kind = "note",
            summary = "اتضافت في ${nb.title}" + (heading?.let { " ← $it" } ?: "")))
        Toast.makeText(activity, "اتضافت في ${nb.title}", Toast.LENGTH_SHORT).show()
    }

    /** Deleting a command: also take back what it did (event, alarm, reminder, notebook items)? */
    fun confirmDelete(activity: Activity, ui: Ui, note: Note) {
        val what = Undo.describe(activity, note)
        if (what == null) { delete(activity, note, alsoAction = false); return }
        val s = Sheet(activity, ui)
        s.title("تمسح $what كمان؟")
        s.action(R.drawable.ic_delete, "امسح الفكرة و$what", ui.danger) { delete(activity, note, alsoAction = true) }
        s.action(R.drawable.ic_close, "امسح الفكرة بس") { delete(activity, note, alsoAction = false) }
        s.show()
    }

    private fun delete(activity: Activity, note: Note, alsoAction: Boolean) {
        val app = activity.applicationContext
        Thread {
            val done = if (alsoAction) Undo.revert(activity, note) else null
            NoteStore.delete(app, note.id)
            activity.runOnUiThread { Toast.makeText(app, done?.let { "اتحذفت، و$it" } ?: "اتحذفت", Toast.LENGTH_SHORT).show() }
        }.start()
    }
}
