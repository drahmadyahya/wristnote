package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var subtitle: android.widget.TextView
    private lateinit var setup: LinearLayout
    private lateinit var setupTitle: android.widget.TextView
    private lateinit var setupBody: android.widget.TextView
    private lateinit var setupBar: Bar
    private lateinit var setupButton: android.widget.TextView
    private lateinit var list: LinearLayout
    private var shownVersion = -1L
    private var lastState = Int.MIN_VALUE
    private var wasReady = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshSetup()
            if (NoteStore.version != shownVersion) render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        build()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        wasReady = ModelManager.isReady(this)
        if (wasReady) TranscribeWorker.enqueuePending(this)
    }

    override fun onResume() { super.onResume(); shownVersion = -1; lastState = Int.MIN_VALUE; handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun build() {
        val gutter = ui.dp(22)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, 0, gutter, 0)
        }

        content.addView(ui.text(34f, ui.semibold, lineMult = 1.1f).apply { text = "أفكاري" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(28) })
        subtitle = ui.text(15f, color = ui.muted)
        content.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })

        // ── one-time setup panel ──
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = ui.rounded(ui.surface, ui.dp(22f))
            visibility = View.GONE
        }
        setupTitle = ui.text(18f, ui.semibold)
        setupBody = ui.text(15f, color = ui.muted, lineMult = 1.4f)
        setupBar = Bar(this, ui.accentSoft, ui.accent)
        setupButton = ui.pillButton("") {
            ModelManager.startDownload(this); lastState = Int.MIN_VALUE; refreshSetup()
        }
        setup.addView(setupTitle)
        setup.addView(setupBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        setup.addView(setupBar, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(18) })
        setup.addView(setupButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        content.addView(setup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(ui.bg); addView(scroll) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom + ui.dp(40))
            insets
        }
        setContentView(root)
    }

    // ───── setup panel ─────
    private fun refreshSetup() {
        val p = ModelManager.poll(this)
        if (p == 100) {
            setup.visibility = View.GONE
            if (!wasReady) { wasReady = true; TranscribeWorker.enqueuePending(this); shownVersion = -1 }
            return
        }
        setup.visibility = View.VISIBLE
        if (p >= 0) {
            setupBar.progress = p / 100f
            setupBody.text = "بينزل… ${ui.num(p)}٪\nممكن تقفل التطبيق، التنزيل هيكمّل لوحده."
        }
        val state = if (p >= 0) 0 else p
        if (state == lastState) return
        lastState = state
        when (p) {
            -1 -> {
                setupTitle.text = "خطوة واحدة قبل ما تبدأ"
                setupBody.text = "نزّل ملف تحويل الصوت لنص مرة واحدة بس. بعدها كل حاجة بتشتغل على موبايلك من غير نت."
                setupBar.visibility = View.GONE
                setupButton.visibility = View.VISIBLE
                setupButton.text = "نزّل الملف (${ui.num(ModelManager.APPROX_MB)} ميجا)"
            }
            -2 -> {
                setupTitle.text = "التنزيل وقف"
                setupBody.text = "اتأكد إنك متوصل بالنت، وجرّب تاني. هيكمّل من الأول."
                setupBar.visibility = View.GONE
                setupButton.visibility = View.VISIBLE
                setupButton.text = "جرّب تاني"
            }
            else -> {
                setupTitle.text = "بنجهّز التحويل لنص"
                setupBar.visibility = View.VISIBLE
                setupButton.visibility = View.GONE
            }
        }
    }

    // ───── notes timeline ─────
    private fun render() {
        shownVersion = NoteStore.version
        list.removeAllViews()
        val notes = NoteStore.all(this)
        val todayKey = ui.dayKey(System.currentTimeMillis())
        val today = notes.count { ui.dayKey(it.id) == todayKey }
        subtitle.text = when {
            notes.isEmpty() -> "فكرتك من الساعة تظهر هنا مكتوبة"
            today > 0 -> "${ui.ideas(today)} النهارده"
            else -> "مفيش أفكار النهارده لسه"
        }

        if (notes.isEmpty()) { list.addView(empty()); return }

        val groups = notes.groupBy { ui.dayKey(it.id) }
        for ((_, group) in groups) {
            list.addView(ui.text(14f, ui.medium, ui.muted).apply { text = ui.dayLabel(group.first().id) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(30); bottomMargin = ui.dp(6) })
            group.forEachIndexed { i, n -> list.addView(entry(n, i == 0, i == group.lastIndex)) }
        }
    }

    private fun entry(n: Note, first: Boolean, last: Boolean): View {
        val ready = ModelManager.isReady(this)
        val dotColor = when { n.text != null -> ui.accent; n.failed -> ui.danger; else -> ui.muted }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.pressable(0x00000000, ui.dp(16f))
            setOnClickListener { NoteSheet(this@MainActivity, ui, n).show() }
        }
        row.addView(Spine(dotColor, first, last), LinearLayout.LayoutParams(ui.dp(22), -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), ui.dp(12), ui.dp(6), ui.dp(14))
        }
        col.addView(ui.text(13f, color = ui.muted).apply { text = ui.clock(n.id) })
        val body = ui.text(18f, lineMult = 1.45f).apply {
            when {
                n.text != null -> text = n.text
                n.failed -> { text = "مقدرناش نحوّلها لنص. المس عشان تحاول تاني."; setTextColor(ui.danger) }
                !ready -> { text = "مستنية ملف التحويل لنص"; setTextColor(ui.muted) }
                else -> { text = "بتتحوّل لنص…"; setTextColor(ui.muted) }
            }
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun empty(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(72), 0, 0)
        }
        box.addView(Dial(this, ui.accent, ui.accent, ui.line), LinearLayout.LayoutParams(ui.dp(132), ui.dp(132)))
        box.addView(ui.text(19f, ui.semibold).apply { text = "لسه مفيش أفكار"; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(28) })
        box.addView(ui.text(15f, color = ui.muted, lineMult = 1.45f).apply {
            text = "اضغط مرتين على زرار الساعة واتكلم.\nالمس الشاشة لما تخلّص، والفكرة هتوصل هنا."
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
        return box
    }

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
