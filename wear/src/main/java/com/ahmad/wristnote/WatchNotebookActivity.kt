package com.ahmad.wristnote

import android.app.Activity
import android.os.Bundle

/** One notebook on the watch: headings and items you can tick. */
class WatchNotebookActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val id = intent.getLongExtra("id", 0L)
        val book = WatchData.cached(this)?.books?.firstOrNull { it.id == id } ?: run { finish(); return }
        val ui = RoundList(this)
        ui.title(book.title)
        if (book.blocks.isEmpty()) ui.col.addView(ui.text("النوتة فاضية.", 14f, ui.muted, center = true))
        for (b in book.blocks) {
            if (b.heading) ui.section(b.text)
            else ui.check(b.text, b.done) { done -> WatchData.toggle(this, book.id, b.id, done) }
        }
        ui.show()
    }
}
