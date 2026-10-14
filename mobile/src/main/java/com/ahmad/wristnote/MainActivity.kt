package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modelBox: LinearLayout
    private lateinit var modelText: TextView
    private lateinit var modelButton: Button
    private lateinit var list: LinearLayout
    private var shownVersion = -1L
    private var wasReady = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshModel()
            if (NoteStore.version != shownVersion) renderNotes()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        wasReady = ModelManager.isReady(this)
        if (wasReady) TranscribeWorker.enqueuePending(this)
    }

    override fun onResume() { super.onResume(); shownVersion = -1; handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun buildUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad + dp(24), pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "فكرتي"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "اضغط مرتين على زرار الساعة، اتكلم، والفكرة توصل هنا مكتوبة."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(12))
        })

        modelBox = card().apply { visibility = View.GONE }
        modelText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        modelButton = Button(this).apply {
            text = "نزّل دلوقتي"
            setOnClickListener { ModelManager.startDownload(this@MainActivity); refreshModel() }
        }
        modelBox.addView(modelText)
        modelBox.addView(modelButton)
        root.addView(modelBox)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refreshModel() {
        val p = ModelManager.poll(this)
        if (p == 100) {
            modelBox.visibility = View.GONE
            if (!wasReady) { wasReady = true; TranscribeWorker.enqueuePending(this) }
            return
        }
        modelBox.visibility = View.VISIBLE
        when (p) {
            -1 -> {
                modelText.text = "عشان التحويل لنص يشتغل من غير نت، نزّل ملف اللغة مرة واحدة بس (حوالي ${ModelManager.APPROX_MB} ميجا). يفضّل على واي فاي."
                modelButton.visibility = View.VISIBLE
                modelButton.text = "نزّل دلوقتي"
            }
            -2 -> {
                modelText.text = "التنزيل فشل. اتأكد من النت وجرّب تاني."
                modelButton.visibility = View.VISIBLE
                modelButton.text = "جرّب تاني"
            }
            else -> {
                modelText.text = "بينزّل ملف اللغة… $p%"
                modelButton.visibility = View.GONE
            }
        }
    }

    private fun renderNotes() {
        shownVersion = NoteStore.version
        list.removeAllViews()
        val notes = NoteStore.all(this)
        if (notes.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "لسه مفيش أفكار. جرّب من الساعة!"
                alpha = 0.6f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        val fmt = SimpleDateFormat("EEEE d MMM · h:mm a", Locale("ar"))
        for (n in notes) {
            val c = card()
            c.addView(TextView(this).apply {
                text = fmt.format(Date(n.id))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.6f
            })
            c.addView(TextView(this).apply {
                text = when {
                    n.text != null -> n.text
                    n.failed -> "التحويل فشل. اضغط \"حاول تاني\"."
                    !ModelManager.isReady(this@MainActivity) -> "مستني ملف اللغة…"
                    else -> "بيتحوّل لنص…"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, dp(4), 0, dp(8))
                setTextIsSelectable(true)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val text = n.text
            if (text != null) {
                for ((label, pkg) in Share.targets(this)) {
                    row.addView(small(label) { startActivity(Share.intent(text, pkg)) })
                }
                row.addView(small("نسخ") {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("note", text))
                    Toast.makeText(this, "اتنسخ", Toast.LENGTH_SHORT).show()
                })
            } else if (n.failed) {
                row.addView(small("حاول تاني") {
                    NoteStore.upsert(this, n.copy(failed = false))
                    TranscribeWorker.enqueue(this, n.id)
                })
            }
            row.addView(small("حذف") {
                AlertDialog.Builder(this)
                    .setMessage("تحذف الفكرة دي؟")
                    .setPositiveButton("حذف") { _, _ -> NoteStore.delete(this, n.id) }
                    .setNegativeButton("لأ", null)
                    .show()
            })
            c.addView(row)
            list.addView(c)
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(28, 128, 128, 128))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun small(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
