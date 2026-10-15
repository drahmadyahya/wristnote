package com.ahmad.wristnote

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Design tokens + small view builders shared by the phone screens. */
class Ui(private val ctx: Context) {
    val regular: Typeface = ctx.resources.getFont(R.font.plex_regular)
    val medium: Typeface = ctx.resources.getFont(R.font.plex_medium)
    val semibold: Typeface = ctx.resources.getFont(R.font.plex_semibold)

    val bg = ctx.getColor(R.color.bg)
    val surface = ctx.getColor(R.color.surface)
    val ink = ctx.getColor(R.color.ink)
    val muted = ctx.getColor(R.color.muted)
    val line = ctx.getColor(R.color.line)
    val accent = ctx.getColor(R.color.accent)
    val accentText = ctx.getColor(R.color.accent_text)
    val accentSoft = ctx.getColor(R.color.accent_soft)
    val onAccent = ctx.getColor(R.color.on_accent)
    val danger = ctx.getColor(R.color.danger)

    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dp(v: Int) = dp(v.toFloat()).toInt()

    fun text(size: Float, face: Typeface = regular, color: Int = ink, lineMult: Float = 1.25f) =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = face
            setTextColor(color)
            setLineSpacing(0f, lineMult)
            textDirection = View.TEXT_DIRECTION_ANY_RTL
            includeFontPadding = false
        }

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun outlined(stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(0); cornerRadius = radius; setStroke(dp(1.2f).toInt(), stroke)
    }

    fun pressable(fill: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf((muted and 0x00FFFFFF) or 0x22000000),
        rounded(fill, radius), rounded(0xFF000000.toInt(), radius)
    )

    fun pillButton(label: String, filled: Boolean = true, onClick: () -> Unit) =
        text(16f, medium, if (filled) onAccent else ink).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(22), 0, dp(22), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(0x22000000),
                if (filled) rounded(accent, dp(26f)) else outlined(line, dp(26f)), rounded(0xFF000000.toInt(), dp(26f))
            )
            setOnClickListener { onClick() }
        }

    fun icon(res: Int, tint: Int, size: Int = 22) = ImageView(ctx).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun iconButton(res: Int, tint: Int, desc: String, onClick: () -> Unit) = FrameLayout(ctx).apply {
        contentDescription = desc
        background = pressable(0, dp(22f))
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(tint) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    fun spacer() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun kindIcon(kind: String) = when (kind) {
        "alarm" -> R.drawable.ic_alarm; "reminder" -> R.drawable.ic_bell
        "event" -> R.drawable.ic_event; "note" -> R.drawable.ic_list; "answer" -> R.drawable.ic_answer; else -> 0
    }

    fun ideas(n: Int) = when {
        n == 1 -> "فكرة واحدة"; n == 2 -> "فكرتين"
        n in 3..10 -> "${Fmt.num(n)} أفكار"; else -> "${Fmt.num(n)} فكرة"
    }

    fun items(n: Int) = when {
        n == 0 -> "فاضية"; n == 1 -> "بند واحد"; n == 2 -> "بندين"
        n in 3..10 -> "${Fmt.num(n)} بنود"; else -> "${Fmt.num(n)} بند"
    }
}

/** Bottom sheet with a rounded top, drag handle and safe-area padding. */
class Sheet(private val activity: Activity, private val ui: Ui) {
    val dialog = Dialog(activity, R.style.Sheet)
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isClickable = true
    }

    fun show(onShown: () -> Unit = {}) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ui.surface)
                val r = ui.dp(28f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isClickable = true
        }
        panel.addView(View(activity).apply { background = ui.rounded(ui.line, ui.dp(3f)) },
            LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(10); bottomMargin = ui.dp(10)
            })
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
        scroll.addView(body)
        panel.addView(scroll)
        val root = FrameLayout(activity).apply {
            setOnClickListener { dialog.dismiss() }
            addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            panel.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(10) + maxOf(b.bottom, ime.bottom))
            root.setPadding(0, b.top + ui.dp(40), 0, 0)
            insets
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setDimAmount(0.45f)
            navigationBarColor = 0; statusBarColor = 0
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnShowListener { onShown() }
        dialog.show()
    }

    fun title(s: String) = body.addView(ui.text(20f, ui.semibold).apply {
        text = s; setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12))
    })

    fun action(icon: Int, label: String, color: Int = ui.ink, onClick: () -> Unit) = body.addView(
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = ui.pressable(0, ui.dp(14f))
            addView(ui.icon(icon, color))
            addView(ui.text(16f, ui.medium, color).apply { text = label },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16) })
            setOnClickListener { dialog.dismiss(); onClick() }
        })

    fun divider() = body.addView(View(activity).apply { setBackgroundColor(ui.line) },
        LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) })

    companion object {
        /** One text field + save button. */
        fun input(activity: Activity, ui: Ui, title: String, initial: String, hint: String,
                  button: String, onSave: (String) -> Unit) {
            val s = Sheet(activity, ui)
            s.title(title)
            val field = EditText(activity).apply {
                setText(initial); setSelection(initial.length)
                this.hint = hint
                typeface = ui.regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(ui.ink); setHintTextColor(ui.muted)
                background = ui.rounded(ui.bg, ui.dp(16f))
                setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 5
                imeOptions = EditorInfo.IME_ACTION_DONE
                textDirection = View.TEXT_DIRECTION_ANY_RTL
            }
            s.body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(14)) })
            val save = ui.pillButton(button) {
                val v = field.text.toString().trim()
                if (v.isNotEmpty()) { s.dialog.dismiss(); onSave(v) }
            }
            s.body.addView(save, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(6)) })
            s.show {
                field.requestFocus()
                s.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                field.post {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(field, 0)
                }
            }
        }
    }
}

/** Thin rounded progress bar (fills from the right in RTL). */
class Bar(ctx: Context, private val track: Int, private val fill: Int) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = track; r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, p)
        val w = width * progress
        p.color = fill; r.set(width - w, 0f, width.toFloat(), h); if (w > 0) c.drawRoundRect(r, h / 2, h / 2, p)
    }
}

/** Round checkbox for notebook items. */
class Check(ctx: Context, private val ui: Ui) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - ui.dp(2f)
        if (checked) {
            p.style = Paint.Style.FILL; p.color = ui.accent; c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = ui.dp(2f); p.color = ui.onAccent
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            val s = r * 0.5f
            val path = android.graphics.Path().apply {
                moveTo(cx - s, cy); lineTo(cx - s * 0.2f, cy + s * 0.75f); lineTo(cx + s, cy - s * 0.6f)
            }
            c.drawPath(path, p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = ui.dp(1.8f); p.color = ui.muted
            c.drawCircle(cx, cy, r, p)
        }
    }
}

/** The watch dial in miniature, for empty states. */
class Dial(ctx: Context, private val ring: Int, private val bars: Int, private val track: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val levels = floatArrayOf(.2f, .5f, .9f, .6f, .3f, .7f, 1f, .5f, .25f, .45f, .8f, .4f, .15f, .3f, .55f, .35f)
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.06f
        p.color = track; c.drawCircle(cx, cy, r * 0.9f, p)
        p.color = ring; c.drawArc(RectF(cx - r * .9f, cy - r * .9f, cx + r * .9f, cy + r * .9f), -90f, 115f, false, p)
        p.color = bars; p.strokeWidth = r * 0.05f
        val n = 48
        for (k in 0 until n) {
            val lv = levels[k % levels.size] * (1f - k / n.toFloat() * 0.6f)
            val a = Math.toRadians(-90.0 + k * 360.0 / n)
            val base = r * 0.5f; val len = r * (0.04f + 0.2f * lv)
            p.alpha = (255 * (1f - k / n.toFloat() * 0.7f)).toInt()
            c.drawLine(cx + base * cos(a).toFloat(), cy + base * sin(a).toFloat(),
                cx + (base + len) * cos(a).toFloat(), cy + (base + len) * sin(a).toFloat(), p)
        }
        p.alpha = 255
    }
}
