package com.ahmad.wristnote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable

/** "مهامي" on the watch: upcoming reminders and the phone's notebooks. */
class TasksActivity : Activity(), DataClient.OnDataChangedListener {
    private val main = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        WatchData.cached(this)?.let { render(it) } ?: render(null)
        Wearable.getDataClient(this).addListener(this)
        Thread { val s = WatchData.load(this); main.post { if (!isFinishing) render(s) } }.start()
    }

    override fun onPause() { super.onPause(); Wearable.getDataClient(this).removeListener(this) }

    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path == "/nerdly/notebooks" })
            Thread { val s = WatchData.load(this); main.post { if (!isFinishing) render(s) } }.start()
    }

    private fun render(s: Snapshot?) {
        val ui = RoundList(this)
        ui.title("مهامي")
        if (s == null || (s.books.isEmpty() && s.reminders.isEmpty())) {
            ui.col.addView(ui.text("لسه مفيش حاجة هنا.\nافتح Nerdly Task على الموبايل مرة، أو قول «اعملي نوتة اسمها…».", 14f, ui.muted, center = true))
        } else {
            if (s.reminders.isNotEmpty()) {
                ui.section("التذكيرات")
                s.reminders.take(4).forEach { ui.row(it.text, it.whenText, null) }
            }
            if (s.books.isNotEmpty()) {
                ui.section("النوت")
                s.books.forEach { b ->
                    val sub = when (b.pending) { 0 -> if (b.blocks.any { !it.heading }) "كله خلص" else "فاضية"; 1 -> "فاضل بند واحد"; 2 -> "فاضل بندين"; else -> "فاضل ${b.pending}" }
                    ui.row(b.title, sub) { startActivity(Intent(this, WatchNotebookActivity::class.java).putExtra("id", b.id)) }
                }
            }
        }
        ui.button("سجّل فكرة") { startActivity(Intent(this, RecordActivity::class.java)) }
        ui.show()
    }
}
