package com.ahmad.wristnote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    /** Ripple on a rounded shape; `fill` may be transparent. */
    fun pressable(fill: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf((muted and 0x00FFFFFF) or 0x22000000),
        rounded(fill, radius), rounded(0xFF000000.toInt(), radius)
    )

    fun pillButton(label: String, onClick: () -> Unit) = text(16f, medium, onAccent).apply {
        text = label
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setPadding(dp(24), 0, dp(24), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(accent, dp(26f)), null)
        setOnClickListener { onClick() }
    }

    // ───── Arabic (Egyptian) wording for time ─────
    private val eg = Locale("ar", "EG")
    fun clock(t: Long): String = SimpleDateFormat("h:mm a", eg).format(Date(t))

    fun dayLabel(t: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(d, now)) return "النهارده"
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(d, now)) return "امبارح"
        val sameYear = d.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        return SimpleDateFormat(if (sameYear) "EEEE d MMMM" else "d MMMM yyyy", eg).format(Date(t))
    }

    fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun num(n: Int): String = String.format(eg, "%d", n)

    fun ideas(n: Int) = when {
        n == 1 -> "فكرة واحدة"
        n == 2 -> "فكرتين"
        n in 3..10 -> "${num(n)} أفكار"
        else -> "${num(n)} فكرة"
    }
}

/** Thin rounded progress bar. */
class Bar(ctx: Context, private val track: Int, private val fill: Int) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = track; r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = fill
        val w = width * progress
        // RTL: fill grows from the right edge
        r.set(width - w, 0f, width.toFloat(), h); if (w > 0) c.drawRoundRect(r, h / 2, h / 2, p)
    }
}

/** The watch dial in miniature, used for the empty state. */
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
