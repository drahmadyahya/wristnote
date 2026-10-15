package com.ahmad.wristnote

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** One notebook: headings with checkable items under them. */
class NotebookActivity : Activity() {

    private lateinit var ui: Ui
    private var id = 0L
    private lateinit var title: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var shown = -1L

    // ───── multi-select ─────
    private val selected = linkedSetOf<Long>()
    private val selecting get() = selected.isNotEmpty()
    private lateinit var top: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var bottomBar: LinearLayout

    private val poll = object : Runnable {
        override fun run() {
            if (Notebooks.version != shown) render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        id = intent.getLongExtra("id", 0L)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        build()
    }

    override fun onResume() { super.onResume(); shown = -1; handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun nb() = Notebooks.get(this, id)

    private fun build() {
        val gutter = ui.dp(20)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(gutter, 0, gutter, 0) }

        top = ui.row(
            ui.iconButton(R.drawable.ic_back, ui.ink, "رجوع") { finish() },
            ui.spacer(),
            ui.iconButton(R.drawable.ic_select, ui.ink, "تحديد") { startSelecting() },
            ui.iconButton(R.drawable.ic_edit, ui.ink, "تغيير الاسم") { rename() },
            ui.iconButton(R.drawable.ic_delete, ui.danger, "حذف النوتة") { deleteBook() },
        )
        selCount = ui.text(18f, ui.semibold)
        selBar = ui.row(
            ui.iconButton(R.drawable.ic_close, ui.ink, "إلغاء التحديد") { clearSelection() },
            selCount.apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) },
            ui.spacer(),
            ui.iconButton(R.drawable.ic_select, ui.ink, "تحديد الكل") { selectAll() },
            ui.iconButton(R.drawable.ic_move, ui.ink, "نقل") { moveSelected() },
            ui.iconButton(R.drawable.ic_delete, ui.danger, "حذف المحدد") { deleteSelected() },
        ).apply { visibility = View.GONE; background = ui.rounded(ui.accentSoft, ui.dp(22f)) }
        content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        content.addView(selBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        title = ui.text(30f, ui.semibold, lineMult = 1.15f)
        content.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(content) }

        val bar = LinearLayout(this).apply {
            bottomBar = this
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.bg)
            setPadding(gutter, ui.dp(10), gutter, ui.dp(10))
            addView(ui.pillButton("عنوان", filled = false) { addHeading() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.pillButton("بند جديد") { addItem(null) }, LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginStart = ui.dp(10) })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(ui.bg)
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bar.setPadding(gutter + b.left, ui.dp(10), gutter + b.right, ui.dp(10) + b.bottom)
            scroll.setPadding(b.left, b.top, b.right, b.bottom + ui.dp(110))
            insets
        }
        setContentView(root)
    }

    private fun render() {
        shown = Notebooks.version
        val nb = nb() ?: run { finish(); return }
        title.text = nb.title
        list.removeAllViews()
        if (nb.blocks.isEmpty()) {
            list.addView(ui.text(15f, color = ui.muted, lineMult = 1.5f).apply {
                text = "النوتة فاضية.\nضيف عنوان زي «المهام»، وبعدين بنود تحته. أو قول للساعة:\n«ضيف في نوتة ${nb.title} …»"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
            return
        }
        // drop selections that no longer exist (e.g. changed from the watch)
        selected.retainAll(nb.blocks.filter { !it.heading }.map { it.id }.toSet())
        for (b in nb.blocks) list.addView(if (b.heading) headingRow(b) else itemRow(b))
        updateBars()
    }

    private fun updateBars() {
        top.visibility = if (selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        bottomBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selCount.text = when (val n = selected.size) { 1 -> "بند واحد"; 2 -> "بندين"; else -> "${Fmt.num(n)} بنود" }
    }

    private fun toggleSelect(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        render()
    }

    private fun startSelecting() {
        val first = nb()?.blocks?.firstOrNull { !it.heading } ?: return
        selected.add(first.id); render()
        android.widget.Toast.makeText(this, "المس البنود اللي عايزها", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clearSelection() { selected.clear(); render() }

    private fun selectAll() {
        val items = nb()?.blocks?.filter { !it.heading }?.map { it.id } ?: return
        if (selected.containsAll(items)) selected.clear() else selected.addAll(items)
        render()
    }

    /** Items under a heading (until the next heading). */
    private fun itemsUnder(h: Block): List<Long> {
        val blocks = nb()?.blocks ?: return emptyList()
        val i = blocks.indexOfFirst { it.id == h.id }
        return blocks.drop(i + 1).takeWhile { !it.heading }.map { it.id }
    }

    private fun deleteSelected() {
        val n = selected.size
        AlertDialog.Builder(this)
            .setMessage(if (n == 1) "تحذف البند ده؟" else "تحذف ${selCount.text}؟")
            .setPositiveButton("حذف") { _, _ ->
                Notebooks.deleteItems(this, id, selected.toSet()); selected.clear(); render()
                android.widget.Toast.makeText(this, "اتحذفت", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("لأ", null).show()
    }

    private fun moveSelected() {
        val s = Sheet(this, ui)
        s.title("تنقل ${selCount.text} لفين؟")
        for (target in Notebooks.all(this)) {
            val label = if (target.id == id) "${target.title} (نفس النوتة)" else target.title
            s.action(R.drawable.ic_list, label) { pickHeading(target) }
        }
        s.action(R.drawable.ic_add, "نوتة جديدة") {
            Sheet.input(this, ui, "نوتة جديدة", "", "اسمها", "إنشاء ونقل") { name ->
                val nb = Notebook(Notebooks.newId(), name, emptyList())
                Notebooks.put(this, nb); doMove(nb.id, null, name)
            }
        }
        s.show()
    }

    private fun pickHeading(target: Notebook) {
        val headings = target.blocks.filter { it.heading }
        if (headings.isEmpty()) { doMove(target.id, null, target.title); return }
        val s = Sheet(this, ui)
        s.title("تحت أنهي عنوان في «${target.title}»؟")
        headings.forEach { h -> s.action(R.drawable.ic_add, h.text) { doMove(target.id, h.id, "${target.title} ← ${h.text}") } }
        s.action(R.drawable.ic_add, "في آخر النوتة") { doMove(target.id, null, target.title) }
        s.show()
    }

    private fun doMove(toId: Long, headingId: Long?, where: String) {
        val n = Notebooks.moveItems(this, id, selected.toSet(), toId, headingId)
        selected.clear(); render()
        android.widget.Toast.makeText(this, "اتنقل ${Fmt.num(n)} لـ $where", android.widget.Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) clearSelection() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun headingRow(b: Block) = ui.row().apply {
        setPadding(0, ui.dp(22), 0, ui.dp(4))
        addView(ui.text(20f, ui.semibold).apply { text = b.text }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!selecting) addView(ui.iconButton(R.drawable.ic_add, ui.accentText, "بند تحت ${b.text}") { addItem(b.id) })
        setOnLongClickListener { if (!selecting) blockMenu(b); true }
        setOnClickListener {
            if (!selecting) return@setOnClickListener
            val ids = itemsUnder(b)
            if (selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
            render()
        }
    }

    private fun itemRow(b: Block) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
        val isSel = b.id in selected
        background = if (isSel) ui.pressable(ui.accentSoft, ui.dp(14f)) else ui.pressable(0, ui.dp(14f))
        val check = Check(this@NotebookActivity, ui).apply {
            checked = b.done
            contentDescription = if (b.done) "اتعمل" else "لسه"
            setOnClickListener { if (selecting) toggleSelect(b.id) else update(b.copy(done = !b.done)) }
        }
        addView(check, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
        addView(ui.text(17f, lineMult = 1.45f).apply {
            text = b.text
            if (b.done) { setTextColor(ui.muted); paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        if (isSel) addView(ui.icon(R.drawable.ic_check, ui.accentText, 20), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginStart = ui.dp(8) })
        setOnClickListener { if (selecting) toggleSelect(b.id) else edit(b) }
        setOnLongClickListener { toggleSelect(b.id); true }
    }

    // ───── edits ─────
    private fun update(b: Block) {
        val nb = nb() ?: return
        Notebooks.put(this, nb.copy(blocks = nb.blocks.map { if (it.id == b.id) b else it }))
        render()
    }

    private fun remove(b: Block) {
        val nb = nb() ?: return
        Notebooks.put(this, nb.copy(blocks = nb.blocks.filter { it.id != b.id })); render()
    }

    private fun edit(b: Block) = Sheet.input(this, ui, if (b.heading) "تعديل العنوان" else "تعديل البند", b.text, "", "حفظ") {
        update(b.copy(text = it))
    }

    private fun blockMenu(b: Block) {
        val s = Sheet(this, ui)
        s.title(b.text)
        s.action(R.drawable.ic_edit, "تعديل") { edit(b) }
        s.action(R.drawable.ic_delete, "حذف", ui.danger) { remove(b) }
        s.show()
    }

    private fun addHeading() = Sheet.input(this, ui, "عنوان جديد", "", "زي: المهام، أفكار، مشتريات البيت", "إضافة") {
        val nb = nb() ?: return@input
        Notebooks.put(this, nb.copy(blocks = nb.blocks + Block(Notebooks.newId(), true, it))); render()
    }

    private fun addItem(headingId: Long?) {
        val nb = nb() ?: return
        val under = headingId?.let { h -> nb.blocks.firstOrNull { it.id == h }?.text }
        Sheet.input(this, ui, if (under != null) "بند تحت «$under»" else "بند جديد", "", "اكتب البند", "إضافة") {
            Notebooks.addItem(this, id, headingId ?: lastHeading(), it); render()
        }
    }

    private fun lastHeading(): Long? = nb()?.blocks?.lastOrNull { it.heading }?.id

    private fun rename() {
        val nb = nb() ?: return
        Sheet.input(this, ui, "اسم النوتة", nb.title, "", "حفظ") { Notebooks.put(this, nb.copy(title = it)); render() }
    }

    private fun deleteBook() {
        val nb = nb() ?: return
        AlertDialog.Builder(this)
            .setMessage("تحذف نوتة «${nb.title}» بكل اللي فيها؟")
            .setPositiveButton("حذف") { _, _ -> Notebooks.delete(this, id); finish() }
            .setNegativeButton("لأ", null)
            .show()
    }
}
