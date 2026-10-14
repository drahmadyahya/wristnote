package com.ahmad.wristnote

import java.util.Calendar

/*
 * Offline understanding of spoken Egyptian Arabic commands.
 * Pure Kotlin (no Android) so it is unit-tested on the JVM.
 *
 *   ظبطلي المنبه الساعة ستة الصبح          -> Alarm
 *   فكرني بكرة الساعة ٥ اكلم احمد            -> Reminder
 *   احفظ معاد الدكتور يوم الخميس الساعة ٧    -> Event
 *   ضيف في نوتة الشغل تحت المهام نراجع العقد  -> AddToNote
 */

sealed class Command {
    data class Alarm(val at: Long, val label: String) : Command()
    data class Reminder(val at: Long, val text: String) : Command()
    data class Event(val at: Long, val title: String, val timed: Boolean) : Command()
    /** anchorId: a heading (add at end of its section) or an item (add right after it). */
    data class AddToNote(val notebookId: Long, val anchorId: Long?, val text: String,
                         val newHeading: String? = null) : Command()
    /** "اعملي نوتة اسمها X وضيف جواها Y" */
    data class CreateNotebook(val title: String, val heading: String?, val items: List<String>) : Command()
    /** User clearly asked for a notebook we don't have. */
    data class NoteNotFound(val name: String, val text: String) : Command()
}

data class NotebookRef(
    val id: Long, val title: String,
    val headings: List<Pair<Long, String>>,
    val items: List<Pair<Long, String>> = emptyList(),
)

object Ar {
    private val marks = Regex("[ً-ٟـٰ]")
    private val edgePunct = Regex("^[\\p{Punct}،؛؟«»“”…]+|[\\p{Punct}،؛؟«»“”…]+$")

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (c in s.replace(marks, "")) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    fun token(raw: String): String = norm(raw).replace(edgePunct, "")

    /** Strips the definite article and attached prepositions for fuzzy matching. */
    fun key(n: String): String {
        var s = n
        for (p in listOf("وبال", "وال", "بال", "فال", "كال", "لل", "ال")) {
            if (s.startsWith(p) && s.length > p.length + 1) { s = s.removePrefix(p); break }
        }
        return s
    }

    fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        // "شغل" ~ "شغلي", "مهام" ~ "مهامي"
        if ((a.startsWith(b) || b.startsWith(a)) && kotlin.math.abs(a.length - b.length) <= 2) return true
        return lev(a, b) <= (if (minOf(a.length, b.length) >= 5) 2 else 1)
    }

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}

object CommandParser {

    private class Toks(text: String) {
        val raw = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val n = raw.map { Ar.token(it) }
        val used = BooleanArray(raw.size)
        operator fun get(i: Int) = n.getOrNull(i) ?: ""
        val size get() = raw.size
        fun use(vararg idx: Int) = idx.forEach { if (it in raw.indices) used[it] = true }
        fun rest(): List<Int> = raw.indices.filter { !used[it] && n[it].isNotEmpty() }
    }

    // ───────────── numbers ─────────────
    private val NUM = mapOf(
        "واحد" to 1, "واحده" to 1, "وحده" to 1,
        "اتنين" to 2, "اثنين" to 2, "اثنان" to 2,
        "تلاته" to 3, "ثلاثه" to 3, "تلات" to 3, "ثلاث" to 3,
        "اربعه" to 4, "اربع" to 4, "خمسه" to 5, "خمس" to 5, "سته" to 6, "ست" to 6,
        "سبعه" to 7, "سبع" to 7, "تمانيه" to 8, "ثمانيه" to 8, "تمنيه" to 8, "تمن" to 8, "ثمان" to 8,
        "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10,
        "حداشر" to 11, "احداشر" to 11, "اتناشر" to 12, "اطناشر" to 12, "اثناشر" to 12,
        "تلتاشر" to 13, "اربعتاشر" to 14, "ربعتاشر" to 14, "خمستاشر" to 15, "ستاشر" to 16,
        "عشرين" to 20, "عشرون" to 20, "تلاتين" to 30, "ثلاثين" to 30, "اربعين" to 40, "خمسين" to 50,
        "الواحده" to 1, "الثانيه" to 2, "التانيه" to 2, "الثالثه" to 3, "التالته" to 3,
        "الرابعه" to 4, "الخامسه" to 5, "السادسه" to 6, "السابعه" to 7, "الثامنه" to 8,
        "التامنه" to 8, "التاسعه" to 9, "العاشره" to 10, "الحاديه" to 11,
    )

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUM[s]

    // ───────────── time words ─────────────
    private val AM = setOf("الصبح", "صباحا", "الصباح", "صباح", "صبح", "الفجر", "فجرا", "ص", "am")
    private val NOON = setOf("الضهر", "الظهر", "ضهرا", "ظهرا")
    private val AFTERNOON = setOf("العصر", "عصرا")
    private val PM = setOf("المغرب", "مساء", "مساءا", "المسا", "بالليل", "الليل", "بليل", "ليلا", "العشا", "م", "pm")
    private val TODAY = setOf("النهارده", "انهارده", "النهاردا", "اليوم", "انهاردا")
    private val TOMORROW = setOf("بكره", "بكرا", "غدا", "غدوه", "بكرى")
    private val WEEKDAYS = mapOf(
        "السبت" to Calendar.SATURDAY, "الحد" to Calendar.SUNDAY, "الاحد" to Calendar.SUNDAY,
        "الاتنين" to Calendar.MONDAY, "الاثنين" to Calendar.MONDAY, "التلات" to Calendar.TUESDAY,
        "الثلاثاء" to Calendar.TUESDAY, "التلاتاء" to Calendar.TUESDAY, "الثلاث" to Calendar.TUESDAY,
        "الاربع" to Calendar.WEDNESDAY, "الاربعاء" to Calendar.WEDNESDAY, "الخميس" to Calendar.THURSDAY,
        "الجمعه" to Calendar.FRIDAY,
    )
    private val CLOCK_WORD = setOf("الساعه", "ساعه")
    private val HOUR_UNIT = setOf("ساعه", "ساعات", "ساعتين")
    private val MIN_UNIT = setOf("دقيقه", "دقايق", "دقائق", "دقيقتين", "دقيقة")
    private val DAY_UNIT = setOf("يوم", "ايام", "يومين")

    private enum class Period { AM, NOON, AFTERNOON, PM }

    class When(val at: Long, val hasClock: Boolean)

    private fun parseTime(t: Toks, now: Long): When? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var dayOffset: Int? = null
        var weekday: Int? = null
        var dayOfMonth: Int? = null
        var relMinutes: Long? = null
        var hour: Int? = null
        var minute = 0
        var period: Period? = null

        // relative: "بعد ساعتين" / "بعد نص ساعه" / "بعد ١٠ دقايق" / "بعد بكره"
        var i = 0
        while (i < t.size) {
            // "بعد ساعة" / "كمان ساعة" / "خلال نص ساعة"
            if (t[i] in setOf("بعد", "كمان", "خلال", "كمانه") && !t.used[i]) {
                if (t[i] == "بعد" && t[i + 1] in TOMORROW) { dayOffset = 2; t.use(i, i + 1); i += 2; continue }
                var j = i + 1
                var amount: Double? = null
                var unitMin = 0L
                when {
                    t[j] == "نص" && t[j + 1] in HOUR_UNIT -> { amount = 0.5; unitMin = 60; j += 2 }
                    t[j] == "ربع" && t[j + 1] in HOUR_UNIT -> { amount = 0.25; unitMin = 60; j += 2 }
                    t[j] == "ساعتين" -> { amount = 2.0; unitMin = 60; j += 1 }
                    t[j] == "دقيقتين" -> { amount = 2.0; unitMin = 1; j += 1 }
                    t[j] == "يومين" -> { amount = 2.0; unitMin = 1440; j += 1 }
                    t[j] == "ساعه" -> { amount = 1.0; unitMin = 60; j += 1 }
                    t[j] == "دقيقه" -> { amount = 1.0; unitMin = 1; j += 1 }
                    t[j] == "يوم" -> { amount = 1.0; unitMin = 1440; j += 1 }
                    t[j] == "اسبوع" -> { amount = 1.0; unitMin = 10080; j += 1 }
                    t[j] == "شويه" || t[j] == "شويا" -> { amount = 30.0; unitMin = 1; j += 1 }
                    num(t[j]) != null -> {
                        val a = num(t[j])!!.toDouble()
                        val u = t[j + 1]
                        unitMin = when (u) {
                            in HOUR_UNIT -> 60; in MIN_UNIT -> 1; in DAY_UNIT -> 1440
                            "اسبوع", "اسابيع" -> 10080; else -> 0
                        }
                        if (unitMin > 0) { amount = a; j += 2 }
                    }
                }
                if (amount != null) {
                    if (unitMin == 60L && t[j] == "ونص") { amount += 0.5; j++ }
                    else if (unitMin == 60L && t[j] == "وربع") { amount += 0.25; j++ }
                    relMinutes = (amount * unitMin).toLong()
                    for (k in i until j) t.use(k)
                    i = j; continue
                }
            }
            i++
        }

        // days
        for (k in 0 until t.size) {
            if (t.used[k]) continue
            val w = t[k]
            when {
                w in TODAY -> { dayOffset = 0; t.use(k) }
                w in TOMORROW -> { dayOffset = 1; t.use(k) }
                w in WEEKDAYS -> { weekday = WEEKDAYS[w]; t.use(k); if (t[k - 1] == "يوم") t.use(k - 1) }
                w == "يوم" && WEEKDAYS.containsKey("ال" + t[k + 1]) -> { weekday = WEEKDAYS["ال" + t[k + 1]]; t.use(k, k + 1) }
                w == "يوم" && t[k + 1].toIntOrNull()?.let { it in 1..31 } == true -> { dayOfMonth = t[k + 1].toInt(); t.use(k, k + 1) }
            }
        }

        // clock: "الساعه ٥" / "الساعه خمسه ونص" / "الساعه ٧ الا ربع" / "٥:٣٠"
        fun readMinutes(start: Int): Int {
            var j = start
            val w = t[j]
            when {
                w == "ونص" -> { minute = 30; t.use(j) }
                w == "وربع" -> { minute = 15; t.use(j) }
                w == "وتلت" || w == "وثلث" -> { minute = 20; t.use(j) }
                w == "و" && t[j + 1] == "نص" -> { minute = 30; t.use(j, j + 1); j++ }
                w == "و" && t[j + 1] == "ربع" -> { minute = 15; t.use(j, j + 1); j++ }
                w == "و" && num(t[j + 1]) != null -> { minute = num(t[j + 1])!!; t.use(j, j + 1); j++ }
                w.startsWith("و") && num(w.drop(1)) != null -> { minute = num(w.drop(1))!!; t.use(j) }
                w == "الا" -> {
                    val x = t[j + 1]
                    val m = when { x == "ربع" -> 15; x == "تلت" || x == "ثلث" -> 20; x == "نص" -> 30; else -> num(x) }
                    if (m != null && m < 60) { minute = -m; t.use(j, j + 1); j++ }
                }
                else -> return j
            }
            j++
            if (t[j] in MIN_UNIT) { t.use(j); j++ }
            return j
        }

        fun readHourToken(k: Int): Boolean {
            val w = t[k]
            val colon = Regex("^(\\d{1,2})[:.](\\d{2})$").find(w)
            when {
                colon != null -> {
                    hour = colon.groupValues[1].toInt(); minute = colon.groupValues[2].toInt(); t.use(k); return true
                }
                (w == "الحاديه" || w == "الثانيه") && t[k + 1] == "عشر" -> {
                    hour = if (w == "الحاديه") 11 else 12; t.use(k, k + 1); readMinutes(k + 2); return true
                }
                num(w)?.let { it in 0..23 } == true -> {
                    hour = num(w); t.use(k); readMinutes(k + 1); return true
                }
            }
            return false
        }

        for (k in 0 until t.size) {
            if (hour != null) break
            if (!t.used[k] && t[k] in CLOCK_WORD && readHourToken(k + 1)) t.use(k)
        }
        if (hour == null) {
            // bare "٥:٣٠", or a number right before a period word / "ونص"
            for (k in 0 until t.size) {
                if (t.used[k]) continue
                val next = t[k + 1]
                val isColon = Regex("^\\d{1,2}[:.]\\d{2}$").matches(t[k])
                val cue = next in AM || next in NOON || next in AFTERNOON || next in PM ||
                        next == "ونص" || next == "وربع" || next == "الا"
                if ((isColon || (num(t[k])?.let { it in 1..12 } == true && cue)) && readHourToken(k)) break
            }
        }

        // period words
        for (k in 0 until t.size) {
            if (t.used[k]) continue
            val p = when (t[k]) {
                in AM -> Period.AM; in NOON -> Period.NOON; in AFTERNOON -> Period.AFTERNOON; in PM -> Period.PM
                else -> null
            }
            // "ص"/"م" only count right after a clock
            if (p != null && (t[k].length > 2 || (hour != null && k > 0 && t.used[k - 1]))) {
                period = p; t.use(k)
                if (t[k - 1] == "بعد") { t.use(k - 1); if (p == Period.NOON) period = Period.PM }
            }
        }

        val hasDay = dayOffset != null || weekday != null || dayOfMonth != null
        if (relMinutes == null && hour == null && !hasDay && period == null) return null

        if (relMinutes != null && hour == null) {
            return When(now + relMinutes * 60_000, relMinutes < 1440)
        }

        // date
        val target = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when {
            relMinutes != null -> target.add(Calendar.MINUTE, relMinutes.toInt())
            dayOffset != null -> target.add(Calendar.DAY_OF_YEAR, dayOffset!!)
            weekday != null -> {
                var diff = (weekday!! - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0 && hour == null) diff = 7
                target.add(Calendar.DAY_OF_YEAR, diff)
            }
            dayOfMonth != null -> {
                if (dayOfMonth!! < cal.get(Calendar.DAY_OF_MONTH)) target.add(Calendar.MONTH, 1)
                target.set(Calendar.DAY_OF_MONTH, dayOfMonth!!.coerceAtMost(target.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
        }

        var h = hour
        val hasClock = h != null || period != null
        if (h == null) {
            h = when (period) { Period.AM -> 9; Period.NOON -> 13; Period.AFTERNOON -> 16; Period.PM -> 20; null -> 9 }
            minute = 0
        } else {
            h = applyPeriod(h, period)
        }

        var m = minute
        var hh = h!!
        if (m < 0) { m += 60; hh -= 1; if (hh < 0) hh = 23 }

        val ambiguous = hour != null && period == null && hour!! in 1..12
        if (hasDay || relMinutes != null) {
            if (ambiguous) hh = when (hour!!) { in 1..7 -> hour!! + 12; else -> hour!! }.let { if (minute < 0) it - 1 else it }
            target.set(Calendar.HOUR_OF_DAY, hh); target.set(Calendar.MINUTE, m)
            if (weekday != null && target.timeInMillis <= now) target.add(Calendar.DAY_OF_YEAR, 7)
            return When(target.timeInMillis, hasClock)
        }

        // no day given: next occurrence
        val candidates = if (ambiguous) listOf(hh, (hh + 12) % 24) else listOf(hh)
        for (c in candidates) {
            target.set(Calendar.HOUR_OF_DAY, c); target.set(Calendar.MINUTE, m)
            if (target.timeInMillis > now + 30_000) return When(target.timeInMillis, hasClock)
        }
        target.set(Calendar.HOUR_OF_DAY, if (ambiguous && hour!! in 1..7) (hh + 12) % 24 else hh)
        target.add(Calendar.DAY_OF_YEAR, 1)
        return When(target.timeInMillis, hasClock)
    }

    private fun applyPeriod(h: Int, p: Period?): Int {
        if (h > 12) return h
        return when (p) {
            Period.AM -> if (h == 12) 0 else h
            Period.NOON -> if (h in 1..5) h + 12 else h
            Period.AFTERNOON -> if (h < 12) h + 12 else h
            Period.PM -> if (h == 12) 0 else h + 12
            null -> h
        }
    }

    // ───────────── triggers ─────────────
    private val ALARM_WORDS = setOf("منبه", "المنبه", "منبهات", "صحيني", "اصحيني", "صحيني", "منبهه")
    private val ALARM_VERBS = setOf("ظبطلي", "اظبطلي", "ظبط", "اظبط", "اضبط", "اضبطلي", "ضبطلي", "حطلي", "اعملي", "عملي",
        "اعمل", "شغلي", "ظبطي", "ظبطيلي", "حط", "على", "علي")
    private val REMIND_PREFIX = listOf("فكرني", "ذكرني", "فكريني", "ذكريني", "افكرني", "تفكرني", "نبهني", "افتكرني")
    private val EVENT_WORDS = setOf("معاد", "ميعاد", "موعد", "المعاد", "الميعاد", "الموعد", "اجتماع", "الاجتماع",
        "التقويم", "تقويم", "الكالندر", "كالندر", "الاجنده", "اجنده")
    private val EVENT_FILLER = setOf("احفظ", "احفظلي", "احفظي", "سجل", "سجلي", "سجللي", "ضيف", "ضيفلي", "حط", "حطلي",
        "عندي", "ده", "دا", "دي", "في", "على", "علي", "المعاد", "الميعاد", "الموعد", "معاد", "ميعاد", "موعد",
        "التقويم", "تقويم", "الكالندر", "كالندر", "الاجنده", "اجنده")
    private val APPOINTMENT_NOUNS = setOf("معاد", "ميعاد", "موعد", "اجتماع", "حجز")
    private val ADD_VERBS = setOf("ضيف", "اضيف", "ضيفي", "اضف", "ضف", "ضيفلي", "ضيفيلي", "تضيف", "تضيفلي", "زود", "زودلي",
        "حط", "حطلي", "حطي", "اكتب", "اكتبلي", "اكتبي", "سجل", "سجلي", "نزل", "نزلي", "دون", "دونلي", "ضيفها", "ضيفه")
    private val CONTAINERS = setOf("نوته", "النوته", "نوتا", "النوتا", "نوتي", "نوت", "النوت", "نوتس", "نوتات", "النوتات",
        "ملاحظه", "الملاحظه", "ملاحظات", "الملاحظات", "ليسته", "الليسته", "لسته", "اللسته", "ليست", "الليست",
        "قايمه", "القايمه", "قائمه", "القائمه", "ورقه", "الورقه", "نوتتي", "نوتة")
    private val LINKERS = setOf("في", "ل", "على", "علي", "جوه", "جوا", "بتاعه", "بتاعت", "بتاع", "بتاعتي", "اللي", "اسمها",
        "اسمه", "عندي", "دي", "ده")
    private val HEADING_WORDS = setOf("عنوان", "العنوان", "بند", "البند", "جزء", "الجزء", "قسم", "القسم", "اسم", "خانه",
        "الخانه", "اسمه", "اللي", "هو", "عناوين")
    private val LEAD_FILLER = setOf("ان", "اني", "انه", "انك", "اننا", "ب", "بان", "ده", "دي", "اللي", "هو", "هي", "بتاع",
        "يا", "ممكن", "لو", "سمحت", "طيب", "بقولك", "عايز", "عاوز", "و")

    fun parse(text: String, notebooks: List<NotebookRef>, now: Long): Command? {
        val t = Toks(text)
        if (t.size == 0) return null

        createNotebook(t)?.let { return it }
        addToNote(Toks(text), notebooks)?.let { return it }

        // alarm
        val alarmIdx = (0 until t.size).firstOrNull { t[it] in ALARM_WORDS }
        if (alarmIdx != null) {
            val tt = Toks(text)
            val w = parseTime(tt, now)
            if (w != null && w.hasClock) {
                for (k in 0 until tt.size) if (tt[k] in ALARM_WORDS || (k <= alarmIdx + 1 && tt[k] in ALARM_VERBS)) tt.use(k)
                return Command.Alarm(w.at, content(tt))
            }
        }

        // reminder
        val remIdx = (0 until t.size).firstOrNull { k -> REMIND_PREFIX.any { t[k].startsWith(it) } }
        if (remIdx != null) {
            val tt = Toks(text)
            val w = parseTime(tt, now)
            if (w != null) {
                tt.use(remIdx)
                val body = stripBi(content(tt))
                return Command.Reminder(w.at, body.ifBlank { "تذكير" })
            }
        }

        // calendar event
        if ((0 until t.size).any { t[it] in EVENT_WORDS }) {
            val tt = Toks(text)
            val w = parseTime(tt, now)
            if (w != null) {
                // drop filler only at the edges so "معاد الدكتور" keeps "الدكتور"
                val idx = tt.rest().toMutableList()
                while (idx.isNotEmpty() && tt[idx.first()] in EVENT_FILLER) idx.removeAt(0)
                while (idx.isNotEmpty() && tt[idx.last()] in EVENT_FILLER) idx.removeAt(idx.lastIndex)
                // keep the noun in "معاد الدكتور" / "اجتماع الفريق"
                if (idx.isNotEmpty()) {
                    val before = idx.first() - 1
                    if (before >= 0 && tt[before] in APPOINTMENT_NOUNS) idx.add(0, before)
                }
                val title = idx.joinToString(" ") { tt.raw[it] }.let(::trimLead)
                return Command.Event(w.at, title.ifBlank { "معاد" }, w.hasClock)
            }
        }
        return null
    }

    private val CREATE_VERBS = setOf("اعملي", "اعمل", "عملي", "اعمليلي", "اعملها", "انشئ", "انشئلي", "انشا", "افتح",
        "افتحلي", "افتحي", "جهزلي", "سويلي", "ابدا", "ابدالي")
    private val NEW_WORDS = setOf("جديده", "جديد", "لي", "ليا", "كده", "واحده")
    private val NAME_WORDS = setOf("اسمها", "اسمه", "بعنوان", "عنوانها", "باسم", "تسميها", "سميها", "سميتها", "نسميها")
    private val SPLIT_WORDS = setOf("وضيف", "ضيف", "وضيفي", "ضيفي", "وضيفلي", "ضيفلي", "وحط", "حط", "وحطلي", "حطلي",
        "وفيها", "فيها", "جواها", "وجواها", "واكتب", "اكتب", "واكتبلي", "وزود", "زود", "وسجل", "سجل", "وتحتها", "وبنودها")
    private val ITEM_LEAD = setOf("جواها", "فيها", "فيه", "عليها", "تحتها", "بند", "بنود", "البنود", "دي", "دول",
        "الحاجات", "حاجات", "كمان", "الاتي", "التالي")

    private fun createNotebook(t: Toks): Command.CreateNotebook? {
        val c = (0 until minOf(5, t.size)).firstOrNull { t[it] in CONTAINERS } ?: return null
        val verb = (0 until c).firstOrNull { t[it] in CREATE_VERBS }
        if (verb == null && t[c + 1] !in NEW_WORDS) return null
        var s = c + 1
        while (t[s] in NEW_WORDS || t[s] in NAME_WORDS) s++
        if (s >= t.size) return null
        val split = (s + 1 until t.size).firstOrNull { t[it] in SPLIT_WORDS }
        val titleEnd = split ?: minOf(t.size, s + 6)
        val title = (s until titleEnd).joinToString(" ") { t.raw[it] }.trim('،', ',', '.', ':', ' ')
        if (title.isBlank()) return null
        var heading: String? = null
        var items = ""
        var j = split?.plus(1) ?: titleEnd
        while (t[j] in ITEM_LEAD) j++
        if (t[j] == "تحت") {
            j++
            while (t[j] in HEADING_WORDS) j++
            if (j < t.size) { heading = t.raw[j].trim('،', ',', ':', '.'); j++ }
        }
        if (j < t.size) items = trimLead((j until t.size).joinToString(" ") { t.raw[it] })
        return Command.CreateNotebook(title, heading, if (items.isBlank()) emptyList() else splitItems(items))
    }

    private val WAW_WORDS = setOf("ورق", "ورقه", "وقت", "ولاد", "وزن", "وش", "وسط", "ورد", "واجب", "وصفه", "واحد", "واحده",
        "وزاره", "وظيفه", "وجبه", "وضع", "وصل", "وصول", "ودان", "وراك", "ورا", "وراه", "وحش", "وحشه", "وسخ")

    /**
     * Splits a spoken list into items: on commas, or on "و" joining short
     * phrases ("لبن وعيش وجبنة", "نراجع العقد واتصل بالمحامي").
     */
    fun splitItems(text: String): List<String> {
        val byComma = text.split(Regex("[،,؛;]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (byComma.size > 1) return byComma
        val words = text.trim().split(Regex("\\s+"))
        val parts = mutableListOf(mutableListOf<String>())
        for ((i, w) in words.withIndex()) {
            val n = Ar.token(w)
            val joins = i > 0 && n.startsWith("و") && n.length >= 3 && n !in WAW_WORDS && n.drop(1) !in setOf("ال")
            if (joins) parts.add(mutableListOf(w.substring(w.indexOf('و') + 1))) else parts.last().add(w)
        }
        val clean = parts.map { it.joinToString(" ").trim() }.filter { it.isNotEmpty() }
        return if (clean.size > 1 && clean.all { it.split(" ").size <= 4 }) clean else listOf(text.trim())
    }

    private fun addToNote(t: Toks, notebooks: List<NotebookRef>): Command? {
        val verb = (0 until minOf(4, t.size)).firstOrNull { t[it] in ADD_VERBS }
        val container = (0 until t.size).firstOrNull { t[it] in CONTAINERS }
        if (verb == null && container == null) return null
        if (verb == null && container!! > 3) return null   // "نوتة" deep inside a sentence is just content
        val from = (verb ?: -1) + 1

        val keys = t.n.map { Ar.key(it) }
        data class Hit(val start: Int, val end: Int, val score: Int)
        fun find(title: String, from: Int): Hit? {
            val tk = title.trim().split(Regex("\\s+")).map { Ar.key(Ar.token(it)) }.filter { it.isNotEmpty() }
            if (tk.isEmpty()) return null
            var best: Hit? = null
            for (s in from..t.size - tk.size) {
                if ((0 until tk.size).all { Ar.close(keys[s + it], tk[it]) }) {
                    val exact = (0 until tk.size).count { keys[s + it] == tk[it] }
                    val h = Hit(s, s + tk.size, tk.sumOf { it.length } * 10 + exact)
                    if (best == null || h.score > best.score) best = h
                }
            }
            return best
        }

        var nb: NotebookRef? = null
        var hit: Hit? = null
        for (b in notebooks) {
            val h = find(b.title, from) ?: continue
            if (hit == null || h.score > hit.score) { nb = b; hit = h }
        }

        if (nb == null) {
            if (container == null) return null
            // "ضيف في نوتة <name>": name not recognised
            var c = container + 1
            while (t[c] in LINKERS) c++
            val spoken = t.raw.getOrNull(c)?.let { Ar.token(it) }.orEmpty()
            if (notebooks.size == 1 && spoken.isNotEmpty() && spoken != "تحت") {
                nb = notebooks[0]; hit = Hit(c, c + 1, 0)
            } else {
                for (k in from..c) t.use(k)
                if (verb != null) t.use(verb)
                return Command.NoteNotFound(t.raw.getOrElse(c) { "" }, content(t))
            }
        }
        val h = hit!!
        verb?.let { t.use(it) }
        for (k in h.start until h.end) t.use(k)
        // container word / linkers right before the title
        var k = h.start - 1
        while (k >= from && (t[k] in CONTAINERS || t[k] in LINKERS)) { t.use(k); k-- }
        container?.let { if (it < h.start) t.use(it) }
        // "للشغل" style attached preposition was matched as the title itself; drop "بتاعتي" after it
        while (t[h.end].let { it == "بتاعتي" || it == "بتاعي" }) { t.use(h.end) ; break }

        // "تحت <heading or item>"
        var anchor: Long? = null
        var newHeading: String? = null
        val tahta = (0 until t.size).firstOrNull { !t.used[it] && (t[it] == "تحت" || t[it] == "تحته") }
        if (tahta != null) {
            var s = tahta + 1
            while (t[s] in HEADING_WORDS) s++
            var best: Pair<Long, Hit>? = null
            for ((id, title) in nb!!.headings + nb.items) {
                val x = find(title, s) ?: continue
                if (x.start - s > 1) continue
                val bonus = if (nb.headings.any { it.first == id }) 1 else 0
                val scored = x.copy(score = x.score * 2 + bonus)
                if (best == null || scored.score > best.second.score) best = id to scored
            }
            if (best != null) {
                anchor = best.first
                for (x in tahta until best.second.end) t.use(x)
            } else if (s < t.size && t.rest().count { it > s } > 0) {
                // unknown heading: create it from the next word
                newHeading = t.raw[s].trim('،', ',', ':', '.')
                for (x in tahta..s) t.use(x)
            } else {
                t.use(tahta)
            }
        }
        val body = content(t)
        if (body.isBlank()) return null
        return Command.AddToNote(nb!!.id, anchor, body, newHeading)
    }

    /** "فكرني بموضوع العربية" -> "موضوع العربية" (keeps real ب-words like بيت، بنك). */
    private val BI_WORDS = setOf("بيت", "البيت", "بنك", "بنزين", "بطاريه", "برنامج", "بنت", "بلد", "باب", "بحر", "برد",
        "بطاطس", "بيض", "بلح", "بسكوت", "بدله", "بوكس", "بنطلون", "بابا", "بكره", "برضو", "بس", "بعد")
    private fun stripBi(s: String): String {
        val parts = s.split(" ").toMutableList()
        val first = parts.firstOrNull() ?: return s
        val n = Ar.token(first)
        if (n.startsWith("ب") && n.length >= 4 && n !in BI_WORDS && !n.startsWith("بت") && !n.startsWith("بي")) {
            parts[0] = first.substring(first.indexOf('ب') + 1)
        }
        return parts.joinToString(" ")
    }

    private fun content(t: Toks): String = trimLead(t.rest().joinToString(" ") { t.raw[it] })

    private fun trimLead(s: String): String {
        val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (parts.isNotEmpty() && Ar.token(parts.first()) in LEAD_FILLER) parts.removeAt(0)
        if (parts.isNotEmpty()) {
            val first = parts.first()
            val n = Ar.token(first)
            if (n.startsWith("بال") && n.length > 4) parts[0] = first.substring(first.indexOf('ا').coerceAtLeast(1))
        }
        return parts.joinToString(" ").trim().trim('،', ',', '.', ':')
    }
}
