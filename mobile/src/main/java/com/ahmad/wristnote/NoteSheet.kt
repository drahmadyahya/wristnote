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
            text = "${Fmt.dayLabel(note.id)}، ${Fmt.clock(note.id)}"
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
            if (note.kind != "idea") {
                s.action(R.drawable.ic_retry, "اعتبرها فكرة عادية") {
                    NoteStore.upsert(activity, note.copy(kind = "idea", summary = null))
                }
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
        s.action(R.drawable.ic_delete, "حذف", ui.danger) {
            NoteStore.delete(activity, note.id)
            Toast.makeText(activity, "اتحذفت", Toast.LENGTH_SHORT).show()
        }
        s.show()
    }
}
