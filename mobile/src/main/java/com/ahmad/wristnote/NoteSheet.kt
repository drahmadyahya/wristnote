package com.ahmad.wristnote

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Bottom sheet for one note: full text and what to do with it. */
class NoteSheet(private val activity: MainActivity, private val ui: Ui, private val note: Note) {

    private val dialog = Dialog(activity, R.style.Sheet)

    fun show() {
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply {
                setColor(ui.surface)
                val r = ui.dp(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(8))
            isClickable = true
        }
        // drag handle
        sheet.addView(View(activity).apply { background = ui.rounded(ui.line, ui.dp(3f)) },
            LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(14) })

        sheet.addView(ui.text(13f, color = ui.muted).apply {
            text = "${ui.dayLabel(note.id)}، ${ui.clock(note.id)}"
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
        })
        val body = ui.text(20f, lineMult = 1.5f).apply {
            text = note.text ?: if (note.failed) "مقدرناش نحوّلها لنص." else "بتتحوّل لنص…"
            if (note.text == null) setTextColor(ui.muted)
            setTextIsSelectable(note.text != null)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
        }
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroller = object : ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
        scroller.addView(body)
        sheet.addView(scroller, LinearLayout.LayoutParams(-1, -2))

        sheet.addView(View(activity).apply { setBackgroundColor(ui.line) },
            LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(6)) })

        val text = note.text
        if (text != null) {
            for ((label, pkg) in Share.targets(activity)) {
                val icon = if (pkg == Share.KEEP) R.drawable.ic_bulb else R.drawable.ic_note
                sheet.addView(action(icon, "حفظ في $label", ui.ink) { activity.startActivity(Share.intent(text, pkg)) })
            }
            sheet.addView(action(R.drawable.ic_copy, "نسخ النص", ui.ink) {
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("فكرة", text))
                Toast.makeText(activity, "اتنسخ", Toast.LENGTH_SHORT).show()
            })
            sheet.addView(action(R.drawable.ic_share, "مشاركة", ui.ink) {
                activity.startActivity(Intent.createChooser(Share.intent(text, null), null))
            })
        } else if (note.failed) {
            sheet.addView(action(R.drawable.ic_retry, "حاول تاني", ui.ink) {
                NoteStore.upsert(activity, note.copy(failed = false))
                TranscribeWorker.enqueue(activity, note.id)
            })
        }
        sheet.addView(action(R.drawable.ic_delete, "حذف", ui.danger) {
            NoteStore.delete(activity, note.id)
            Toast.makeText(activity, "اتحذفت", Toast.LENGTH_SHORT).show()
        })

        // dim area above the sheet closes it
        val root = FrameLayout(activity).apply {
            setOnClickListener { dialog.dismiss() }
            addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sheet.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(8) + b.bottom)
            root.setPadding(0, b.top + ui.dp(48), 0, 0)
            insets
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setDimAmount(0.45f)
            navigationBarColor = 0
            statusBarColor = 0
        }
        dialog.show()
    }

    private fun action(icon: Int, label: String, color: Int, onClick: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = ui.pressable(0x00000000, ui.dp(14f))
            addView(ImageView(activity).apply {
                setImageResource(icon)
                setColorFilter(color)
            }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            addView(ui.text(16f, ui.medium, color).apply { text = label },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16) })
            setOnClickListener { dialog.dismiss(); onClick() }
        }
}
