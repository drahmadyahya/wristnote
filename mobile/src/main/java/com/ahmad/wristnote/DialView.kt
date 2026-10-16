package com.ahmad.wristnote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The watch's recorder dial, on the phone (same drawing, same colours).
 * Everything is sized from the view's radius.
 *
 *  - outer ring: time used out of the 60 s limit
 *  - 72 radial bars: live voice level, travelling around the dial
 *  - centre: elapsed time
 */
class DialView(context: Context) : View(context) {

    enum class Mode { RECORDING, SENT, QUEUED, ERROR, SHORT, NEED_MIC }

    var mode = Mode.RECORDING
        private set
    var elapsedMs = 0L
    var maxMs = 60_000L
    /** Line under the timer while recording. */
    var hint = ""
    /** Round "watch face" behind the dial (0 = none). */
    var face = 0
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bars = 72
    private val levels = FloatArray(bars)
    private var head = 0

    private val teal = Color.parseColor("#F2BE3C")
    private val tealDim = Color.parseColor("#3A2F12")
    private val rec = Color.parseColor("#FF5C5C")
    private val muted = Color.parseColor("#9C978C")

    private val medium: Typeface = context.resources.getFont(R.font.plex_medium)
    private val regular: Typeface = context.resources.getFont(R.font.plex_regular)

    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = tealDim; strokeCap = Paint.Cap.ROUND }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = teal; strokeCap = Paint.Cap.ROUND }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = teal }
    private val timeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = medium }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textAlign = Paint.Align.CENTER; typeface = regular }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rec }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }

    private val arc = RectF()
    private val path = Path()

    private var resultProgress = 0f   // 0 -> 1 when switching to a result screen
    private var pulse = 0f
    private var message = ""

    private val pulser = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    init {
        contentDescription = "بيسجّل"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulser.start() }
    override fun onDetachedFromWindow() { pulser.cancel(); super.onDetachedFromWindow() }

    /** level in 0..1, called from the audio thread ~20x per second */
    fun pushLevel(level: Float) {
        synchronized(levels) {
            head = (head + 1) % bars
            levels[head] = level.coerceIn(0f, 1f)
        }
        postInvalidateOnAnimation()
    }

    /** Changes only the caption, without replaying the badge animation. */
    fun caption(text: String) { message = text; contentDescription = text; invalidate() }

    fun show(newMode: Mode, text: String) {
        if (newMode == mode && mode != Mode.RECORDING) { caption(text); return }
        mode = newMode
        message = text
        if (newMode == Mode.NEED_MIC) { resultProgress = 1f; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { resultProgress = it.animatedValue as Float; invalidate() }
            start()
        }
        contentDescription = text
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (face != 0) { facePaint.color = face; c.drawCircle(cx, cy, r, facePaint) }

        // ── outer progress ring ──
        val stroke = r * 0.035f
        val inset = r * 0.06f
        ringTrack.strokeWidth = stroke
        ringFill.strokeWidth = stroke
        arc.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
        c.drawArc(arc, 0f, 360f, false, ringTrack)
        val frac = if (mode == Mode.RECORDING) (elapsedMs.toFloat() / maxMs).coerceIn(0f, 1f) else 1f
        ringFill.color = when (mode) { Mode.ERROR, Mode.NEED_MIC -> rec; Mode.SHORT -> muted; else -> teal }
        if (frac > 0f) c.drawArc(arc, -90f, 360f * frac, false, ringFill)

        // ── radial voice bars (fade out on result) ──
        val barFade = if (mode == Mode.RECORDING) 1f else (1f - resultProgress).coerceIn(0f, 1f)
        if (barFade > 0f) {
            val base = r * 0.60f
            bar.strokeWidth = r * 0.022f
            synchronized(levels) {
                for (k in 0 until bars) {
                    val lv = levels[(head - k + bars) % bars]
                    val len = r * (0.025f + 0.17f * lv) * barFade
                    val a = Math.toRadians((-90.0 + k * 360.0 / bars))
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    bar.alpha = ((255 * (1f - k / bars.toFloat() * 0.65f)) * barFade).toInt()
                    c.drawLine(cx + base * ca, cy + base * sa, cx + (base + len) * ca, cy + (base + len) * sa, bar)
                }
            }
        }

        if (mode == Mode.RECORDING) {
            // recording dot + label
            smallText.textSize = r * 0.095f
            smallText.color = muted
            val labelY = cy - r * 0.28f
            val dotR = r * 0.028f * (0.75f + 0.25f * kotlin.math.abs(sin(pulse * Math.PI)).toFloat())
            val label = "بيسجّل"
            val lw = smallText.measureText(label)
            c.drawCircle(cx + lw / 2f + r * 0.06f, labelY - smallText.textSize * 0.32f, dotR, dot)
            c.drawText(label, cx - r * 0.02f, labelY, smallText)

            timeText.textSize = r * 0.36f
            c.drawText(formatTime(elapsedMs), cx, cy + timeText.textSize * 0.36f, timeText)

            if (hint.isNotEmpty()) {
                smallText.color = Color.WHITE
                c.drawText(hint, cx, cy + r * 0.36f, smallText)
            }
            return
        }

        // ── result: badge + message ──
        val p = resultProgress
        val br = r * 0.24f * p
        badge.color = when (mode) { Mode.ERROR, Mode.NEED_MIC -> rec; Mode.SHORT -> muted; else -> teal }
        val by = cy - r * 0.10f
        c.drawCircle(cx, by, br, badge)
        mark.strokeWidth = r * 0.045f
        if (p > 0.5f) {
            path.reset()
            val s = br * 0.42f
            when (mode) {
                Mode.SENT -> { path.moveTo(cx - s, by); path.lineTo(cx - s * 0.25f, by + s * 0.7f); path.lineTo(cx + s, by - s * 0.65f) }
                Mode.QUEUED -> { // clock hands
                    path.moveTo(cx, by - s * 0.9f); path.lineTo(cx, by); path.lineTo(cx + s * 0.65f, by + s * 0.4f)
                }
                Mode.SHORT -> { path.moveTo(cx - s * 0.7f, by); path.lineTo(cx + s * 0.7f, by) }
                else -> { path.moveTo(cx, by - s * 0.8f); path.lineTo(cx, by + s * 0.2f); path.moveTo(cx, by + s * 0.75f); path.lineTo(cx, by + s * 0.76f) }
            }
            c.drawPath(path, mark)
        }
        smallText.color = Color.WHITE
        smallText.textSize = r * 0.105f
        val lines = message.split("\n")
        lines.forEachIndexed { i, line ->
            smallText.color = if (i == 0) Color.WHITE else muted
            smallText.textSize = if (i == 0) r * 0.11f else r * 0.085f
            c.drawText(line, cx, cy + r * 0.32f + i * r * 0.15f, smallText)
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale("ar", "EG"), "%d:%02d", s / 60, s % 60)
    }

    companion object {
        val decel = DecelerateInterpolator()
    }
}
