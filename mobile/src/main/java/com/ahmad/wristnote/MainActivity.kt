package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var banners: LinearLayout
    private lateinit var tabIdeas: TextView
    private lateinit var tabBooks: TextView
    private lateinit var list: LinearLayout
    private var tab = 0
    private var shown = ""
    private var hadModel = false

    private val refresher = object : Runnable {
        override fun run() {
            refresh(false)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        tab = savedInstanceState?.getInt("tab") ?: 0
        build()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        hadModel = ModelManager.usable(this) != null
        if (hadModel) TranscribeWorker.enqueuePending(this)
        Reminders.rearmAll(this)
        Brief.schedule(this)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", tab) }
    override fun onResume() { super.onResume(); refresh(true); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun build() {
        val gutter = ui.dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, 0, gutter, 0)
        }

        // header: logo + name ……… settings
        val logo = ImageView(this).apply { setImageResource(R.drawable.logo) }
        val name = ui.text(24f, ui.semibold).apply { text = "Nerdly Task" }
        val header = ui.row(
            logo, name, ui.spacer(),
            ui.iconButton(R.drawable.ic_settings, ui.ink, "الإعدادات") { SettingsSheet.show(this, ui) },
        )
        logo.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
        name.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) }
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })

        banners = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(banners, LinearLayout.LayoutParams(-1, -2))

        // segmented tabs
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(ui.surface, ui.dp(24f))
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        tabIdeas = segTab("الأفكار") { tab = 0; refresh(true) }
        tabBooks = segTab("النوت") { tab = 1; refresh(true) }
        seg.addView(tabIdeas, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        seg.addView(tabBooks, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        content.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(ui.bg); addView(scroll) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            scroll.setPadding(b.left, b.top, b.right, b.bottom + ui.dp(40))
            insets
        }
        setContentView(root)
    }

    private fun segTab(label: String, onClick: () -> Unit) = ui.text(15f, ui.medium).apply {
        text = label; gravity = Gravity.CENTER; setOnClickListener { onClick() }
    }

    // ───── state → screen ─────
    private fun refresh(force: Boolean) {
        val model = ModelManager.usable(this)
        if (model != null && !hadModel) { hadModel = true; TranscribeWorker.enqueuePending(this) }
        val sel = ModelManager.selected(this)
        val progress = ModelManager.poll(this, sel)
        val missing = SettingsSheet.missing(this)
        val sig = "$tab|${NoteStore.version}|${Notebooks.version}|${model}|$progress|$missing"
        if (!force && sig == shown) return
        shown = sig

        tabIdeas.applyTab(tab == 0); tabBooks.applyTab(tab == 1)
        renderBanners(model, sel, progress, missing)
        list.removeAllViews()
        if (tab == 0) renderIdeas() else renderBooks()
    }

    private fun TextView.applyTab(on: Boolean) {
        background = if (on) ui.rounded(ui.accent, ui.dp(20f)) else null
        setTextColor(if (on) ui.onAccent else ui.muted)
    }

    private fun renderBanners(model: ModelManager.Model?, sel: ModelManager.Model, p: Int, missing: Int) {
        banners.removeAllViews()
        if (model == null) {
            // first run: nothing can be transcribed yet
            val card = card()
            card.addView(ui.text(18f, ui.semibold).apply {
                text = if (p in 0..99) "بنجهّز التحويل لنص" else if (p == -2) "التنزيل وقف" else "خطوة واحدة قبل ما تبدأ"
            })
            card.addView(ui.text(15f, color = ui.muted, lineMult = 1.4f).apply {
                text = when {
                    p in 0..99 -> "بينزل… ${Fmt.num(p)}٪\nممكن تقفل التطبيق، التنزيل هيكمّل لوحده."
                    p == -2 -> "اتأكد إنك متوصل بالنت وجرّب تاني."
                    else -> "نزّل ملف تحويل الصوت لنص مرة واحدة. بعدها كل حاجة بتشتغل على موبايلك من غير نت."
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            if (p in 0..99) {
                card.addView(Bar(this, ui.accentSoft, ui.accent).apply { progress = p / 100f },
                    LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(16) })
            } else {
                card.addView(ui.pillButton(if (p == -2) "جرّب تاني" else "نزّل الملف (${Fmt.num(sel.mb)} ميجا)") {
                    ModelManager.startDownload(this, sel); refresh(true)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
            }
            banners.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        } else if (model != sel) {
            // using the fast model while the accurate one is missing / downloading
            val msg = if (p in 0..99) "بينزل ملف الدقة العالية… ${Fmt.num(p)}٪" else "الكلام هيبقى أدق لو نزّلت ملف الدقة العالية"
            banners.addView(strip(msg, if (p in 0..99) null else "نزّل") { ModelManager.startDownload(this, sel); refresh(true) })
        }
        if (missing > 0) {
            val msg = if (missing == 1) "فاضل إذن واحد عشان الأوامر الصوتية تشتغل" else "فاضل ${Fmt.num(missing)} أذونات عشان الأوامر الصوتية تشتغل"
            banners.addView(strip(msg, "فعّل") { SettingsSheet.show(this, ui) })
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
        background = ui.rounded(ui.surface, ui.dp(22f))
    }

    private fun strip(msg: String, action: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12))
        background = ui.rounded(ui.accentSoft, ui.dp(18f))
        addView(ui.text(14f, color = ui.ink, lineMult = 1.35f).apply { text = msg }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) addView(ui.text(14f, ui.semibold, ui.onAccent).apply {
            text = action
            setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
            background = ui.rounded(ui.accent, ui.dp(16f))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) }
    }

    // ───── ideas timeline ─────
    private fun renderIdeas() {
        val notes = NoteStore.all(this)
        if (notes.isEmpty()) { list.addView(emptyIdeas()); return }
        val today = Fmt.dayKey(System.currentTimeMillis())
        val n = notes.count { Fmt.dayKey(it.id) == today }
        list.addView(ui.text(14f, color = ui.muted).apply {
            text = if (n > 0) "${ui.ideas(n)} النهارده" else "مفيش أفكار النهارده لسه"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })

        for ((_, group) in notes.groupBy { Fmt.dayKey(it.id) }) {
            list.addView(ui.text(14f, ui.medium, ui.muted).apply { text = Fmt.dayLabel(group.first().id) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24); bottomMargin = ui.dp(4) })
            group.forEachIndexed { i, note -> list.addView(entry(note, i == 0, i == group.lastIndex)) }
        }
    }

    private fun entry(n: Note, first: Boolean, last: Boolean): View {
        val dot = when { n.failed -> ui.danger; n.text == null -> ui.muted; else -> ui.accent }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.pressable(0, ui.dp(16f))
            setOnClickListener { NoteSheet.show(this@MainActivity, ui, n) }
        }
        row.addView(Spine(dot, first, last), LinearLayout.LayoutParams(ui.dp(22), -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), ui.dp(12), ui.dp(6), ui.dp(14))
        }
        col.addView(ui.text(13f, color = ui.muted).apply { text = Fmt.clock(n.id) })
        col.addView(ui.text(18f, lineMult = 1.45f).apply {
            when {
                n.text != null -> text = n.text
                n.failed -> { text = "مقدرناش نحوّلها لنص. المس عشان تحاول تاني."; setTextColor(ui.danger) }
                ModelManager.usable(this@MainActivity) == null -> { text = "مستنية ملف التحويل لنص"; setTextColor(ui.muted) }
                else -> { text = "بتتحوّل لنص…"; setTextColor(ui.muted) }
            }
            maxLines = 6; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        if (n.summary != null) {
            val k = ui.kindIcon(n.kind)
            val chip = ui.row().apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6))
                background = ui.rounded(ui.accentSoft, ui.dp(12f))
                if (k != 0) addView(ui.icon(k, ui.accentText, 16))
                addView(ui.text(13f, ui.medium).apply { text = n.summary; maxLines = 2 },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
            }
            col.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun emptyIdeas(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ui.dp(56), 0, 0)
        addView(Dial(this@MainActivity, ui.accent, ui.accent, ui.line), LinearLayout.LayoutParams(ui.dp(128), ui.dp(128)))
        addView(ui.text(19f, ui.semibold).apply { text = "لسه مفيش أفكار"; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(26) })
        addView(ui.text(15f, color = ui.muted, lineMult = 1.45f).apply {
            text = "اضغط مرتين على زرار الساعة واتكلم.\nقول فكرة، أو أمر زي «فكرني بكرة الساعة ٥»."
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
    }

    // ───── notebooks ─────
    private fun renderBooks() {
        val books = Notebooks.all(this)
        list.addView(ui.text(14f, color = ui.muted, lineMult = 1.4f).apply {
            text = "اعمل نوتة هنا، وبعدين قول للساعة «ضيف في نوتة … تحت …»."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18); bottomMargin = ui.dp(6) })

        for (nb in books) {
            val headings = nb.blocks.count { it.heading }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(16), ui.dp(14), ui.dp(16))
                background = ui.pressable(ui.surface, ui.dp(20f))
                setOnClickListener { openBook(nb.id) }
            }
            c.addView(FrameLayout(this).apply {
                background = ui.rounded(ui.accentSoft, ui.dp(14f))
                addView(ui.icon(R.drawable.ic_list, ui.accentText), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(18f, ui.semibold).apply { text = nb.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text(13f, color = ui.muted).apply {
                    text = ui.items(nb.itemCount) + if (headings > 0) " في ${Fmt.num(headings)} ${if (headings == 1) "عنوان" else "عناوين"}" else ""
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
            list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }

        list.addView(ui.pillButton("نوتة جديدة", filled = books.isEmpty()) {
            Sheet.input(this, ui, "نوتة جديدة", "", "اسمها، زي: الشغل أو المشتريات", "إنشاء") { title ->
                val nb = Notebook(Notebooks.newId(), title, emptyList())
                Notebooks.put(this, nb)
                openBook(nb.id)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
    }

    private fun openBook(id: Long) = startActivity(Intent(this, NotebookActivity::class.java).putExtra("id", id))

    /** Vertical timeline rail with a dot aligned to the time row. */
    private inner class Spine(private val dot: Int, private val first: Boolean, private val last: Boolean) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val x = width / 2f
            val dy = ui.dp(19f)
            p.color = ui.line; p.strokeWidth = ui.dp(1.5f)
            c.drawLine(x, if (first) dy else 0f, x, if (last) dy else height.toFloat(), p)
            p.color = ui.bg; c.drawCircle(x, dy, ui.dp(7f), p)
            p.color = dot; c.drawCircle(x, dy, ui.dp(4.5f), p)
        }
    }
}
