package com.ahmad.wristnote

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Egyptian-Arabic wording for dates and times. */
object Fmt {
    private val eg = Locale("ar", "EG")

    fun clock(t: Long): String = SimpleDateFormat("h:mm a", eg).format(Date(t))

    fun dayLabel(t: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = t }
        val c = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(d, c)) return "النهارده"
        c.add(Calendar.DAY_OF_YEAR, 1); if (same(d, c)) return "بكرة"
        c.add(Calendar.DAY_OF_YEAR, -2); if (same(d, c)) return "امبارح"
        val sameYear = d.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        return SimpleDateFormat(if (sameYear) "EEEE d MMMM" else "d MMMM yyyy", eg).format(Date(t))
    }

    /** "بكرة الساعة ٥:٠٠ م" */
    fun whenText(t: Long) = "${dayLabel(t)} الساعة ${clock(t)}"

    fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun num(n: Int): String = String.format(eg, "%d", n)
}
