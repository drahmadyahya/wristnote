package com.ahmad.wristnote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full answer on a round screen: amber title, scrollable body. */
class AnswerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    // a newer answer arrives while this screen is open: show it instead of the old one
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: android.content.Intent) {
        val title = intent.getStringExtra("title") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val w = resources.displayMetrics.widthPixels
        // keep text inside the circle: ~15% side inset, more at the top/bottom
        val side = (w * 0.15f).toInt()
        val medium = resources.getFont(R.font.plex_medium)
        val regular = resources.getFont(R.font.plex_regular)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(side, (w * 0.17f).toInt(), side, (w * 0.22f).toInt())
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = medium
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = body
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = regular
            setLineSpacing(0f, 1.3f)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (w * 0.04f).toInt() })
        col.addView(TextView(this).apply {
            text = "تمام"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = medium
            gravity = Gravity.CENTER
            setPadding(0, (w * 0.035f).toInt(), 0, (w * 0.035f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.amber)); cornerRadius = w * 0.08f
            }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((w * 0.45f).toInt(), -2).apply { topMargin = (w * 0.06f).toInt() })
        col.addView(TextView(this).apply {
            text = "اسمعها"
            setTextColor(getColor(R.color.amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = medium
            gravity = Gravity.CENTER
            setPadding(0, (w * 0.03f).toInt(), 0, (w * 0.03f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(3, getColor(R.color.amber)); cornerRadius = w * 0.08f
            }
            setOnClickListener { Speaker.sayAsync(this@AnswerActivity, "$title. $body") }
        }, LinearLayout.LayoutParams((w * 0.45f).toInt(), -2).apply { topMargin = (w * 0.03f).toInt() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            addView(col)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(scroll)
        // lets the rotating bezel / crown scroll the text
        scroll.requestFocus()
    }
}
