package com.ahmad.wristnote

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small view kit for scrolling lists on a round watch screen. */
class RoundList(private val a: Activity) {
    val w = a.resources.displayMetrics.widthPixels
    private val medium = a.resources.getFont(R.font.plex_medium)
    private val regular = a.resources.getFont(R.font.plex_regular)
    val amber = a.getColor(R.color.amber)
    val muted = Color.parseColor("#9C978C")
    val col = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding((w * 0.12f).toInt(), (w * 0.14f).toInt(), (w * 0.12f).toInt(), (w * 0.24f).toInt())
    }
    val scroll = ScrollView(a).apply {
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isFocusable = true; isFocusableInTouchMode = true
        addView(col)
    }

    fun px(f: Float) = (w * f).toInt()

    fun text(s: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false, center: Boolean = false) =
        TextView(a).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = if (bold) medium else regular
            setLineSpacing(0f, 1.2f)
            gravity = if (center) Gravity.CENTER else Gravity.START
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }

    fun title(s: String) = col.addView(text(s, 17f, amber, bold = true, center = true),
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(0.03f) })

    fun section(s: String) = col.addView(text(s, 12f, muted, bold = true),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0.04f); bottomMargin = px(0.01f) })

    /** A tappable rounded row. */
    fun row(main: String, sub: String?, onClick: (() -> Unit)?) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(0.05f), px(0.03f), px(0.05f), px(0.03f))
        background = GradientDrawable().apply { setColor(Color.parseColor("#1F1C17")); cornerRadius = px(0.07f).toFloat() }
        addView(text(main, 15f, bold = true))
        if (sub != null) addView(text(sub, 12f, muted))
        if (onClick != null) setOnClickListener { onClick() }
    }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(0.02f) }) }

    /** A checkable item: circle + text. */
    fun check(textS: String, done: Boolean, onToggle: (Boolean) -> Unit) {
        var state = done
        val label = text(textS, 15f, if (done) muted else Color.WHITE)
        val box = object : View(a) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = width / 2f - 3
                if (state) {
                    p.style = Paint.Style.FILL; p.color = amber; c.drawCircle(width / 2f, height / 2f, r, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = width * 0.1f; p.color = Color.BLACK
                    p.strokeCap = Paint.Cap.ROUND
                    val s = r * 0.5f; val cx = width / 2f; val cy = height / 2f
                    c.drawPath(Path().apply { moveTo(cx - s, cy); lineTo(cx - s * 0.2f, cy + s * 0.7f); lineTo(cx + s, cy - s * 0.6f) }, p)
                } else {
                    p.style = Paint.Style.STROKE; p.strokeWidth = width * 0.08f; p.color = muted
                    c.drawCircle(width / 2f, height / 2f, r, p)
                }
            }
        }
        fun paint() {
            label.setTextColor(if (state) muted else Color.WHITE)
            label.paintFlags = if (state) label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            box.invalidate()
        }
        paint()
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(0.022f), 0, px(0.022f))
            addView(box, LinearLayout.LayoutParams(px(0.075f), px(0.075f)))
            addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(0.03f) })
            setOnClickListener {
                state = !state; paint(); onToggle(state)
                try { (a.getSystemService(android.os.Vibrator::class.java))?.vibrate(
                    android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
            }
        }
        col.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = col.addView(
        text(label, 14f, if (filled) Color.BLACK else amber, bold = true, center = true).apply {
            setPadding(0, px(0.03f), 0, px(0.03f))
            background = GradientDrawable().apply {
                cornerRadius = px(0.08f).toFloat()
                if (filled) setColor(amber) else { setColor(Color.TRANSPARENT); setStroke(3, amber) }
            }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(px(0.5f), -2).apply { topMargin = px(0.05f); gravity = Gravity.CENTER_HORIZONTAL })

    fun show() { a.setContentView(scroll); scroll.requestFocus() }
}
