package com.ahmad.wristnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CommandParserTest {
    // Wednesday 14 Oct 2026, 10:00
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 14, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val books = listOf(
        NotebookRef(1, "الشغل", listOf(10L to "المهام", 11L to "أفكار"), listOf(20L to "مراجعة الميزانية")),
        NotebookRef(2, "المشتريات", emptyList()),
    )

    private fun at(c: Long): String {
        val k = Calendar.getInstance().apply { timeInMillis = c }
        return "%d/%d %02d:%02d".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))
    }

    private fun p(s: String) = CommandParser.parse(s, books, now)

    @Test fun alarmMorning() {
        val c = p("ظبطلي المنبه الساعة ستة الصبح") as Command.Alarm
        assertEquals("15/10 06:00", at(c.at)); assertEquals("", c.label)
    }
    @Test fun alarmHalf() {
        val c = p("صحيني الساعه ٧ ونص") as Command.Alarm
        assertEquals("14/10 19:30", at(c.at))
    }
    @Test fun alarmDigitsColon() {
        val c = p("اعملي منبه 5:45 الصبح") as Command.Alarm
        assertEquals("15/10 05:45", at(c.at))
    }
    @Test fun reminderTomorrow() {
        val c = p("فكرني بكرة الساعة ٥ اكلم احمد") as Command.Reminder
        assertEquals("15/10 17:00", at(c.at)); assertEquals("اكلم احمد", c.text)
    }
    @Test fun reminderRelative() {
        val c = p("فكرني بعد ساعتين اشرب الدوا") as Command.Reminder
        assertEquals("14/10 12:00", at(c.at)); assertEquals("اشرب الدوا", c.text)
    }
    @Test fun reminderHalfHour() {
        val c = p("فكّرني بعد نص ساعة بالغسيل") as Command.Reminder
        assertEquals("14/10 10:30", at(c.at)); assertEquals("الغسيل", c.text)
    }
    @Test fun reminderAfternoon() {
        val c = p("فكرني الساعة ٣ العصر ان اروح البنك") as Command.Reminder
        assertEquals("14/10 15:00", at(c.at)); assertEquals("اروح البنك", c.text)
    }
    @Test fun reminderToMinus() {
        val c = p("فكرني الساعه تسعه الا ربع بالليل") as Command.Reminder
        assertEquals("14/10 20:45", at(c.at))
    }
    @Test fun eventWeekday() {
        val c = p("احفظ معاد الدكتور يوم الخميس الساعة ٧") as Command.Event
        assertEquals("15/10 19:00", at(c.at)); assertEquals("معاد الدكتور", c.title); assertTrue(c.timed)
    }
    @Test fun eventMeeting() {
        val c = p("عندي اجتماع بكرة الساعة ١٠ الصبح") as Command.Event
        assertEquals("15/10 10:00", at(c.at)); assertEquals("اجتماع", c.title)
    }
    @Test fun addUnderHeading() {
        val c = p("ضيف في نوتة الشغل تحت المهام نراجع العقد") as Command.AddToNote
        assertEquals(1L, c.notebookId); assertEquals(10L, c.anchorId); assertEquals("نراجع العقد", c.text)
    }
    @Test fun addHeadingWordAndEarlyContent() {
        val c = p("ضيف لبن وعيش في المشتريات") as Command.AddToNote
        assertEquals(2L, c.notebookId); assertNull(c.anchorId); assertEquals("لبن وعيش", c.text)
    }
    @Test fun addUnderTitledHeading() {
        val c = p("ضيف للشغل تحت عنوان افكار تطبيق للساعة") as Command.AddToNote
        assertEquals(1L, c.notebookId); assertEquals(11L, c.anchorId); assertEquals("تطبيق للساعة", c.text)
    }
    @Test fun plainIdea() {
        assertNull(p("فكرة حلوة لمشروع جديد عن القهوة"))
    }
    @Test fun normalizesDigits() {
        assertEquals("15", Ar.norm("١٥"))
    }

    @Test fun addPoliteWithLinkers() {
        val c = p("ممكن تضيف في النوتة بتاعة الشغل تحت بند المهام نراجع العقد") as Command.AddToNote
        assertEquals(1L, c.notebookId); assertEquals(10L, c.anchorId); assertEquals("نراجع العقد", c.text)
    }
    @Test fun addWithoutVerb() {
        val c = p("نوتة الشغل تحت المهام نراجع العقد") as Command.AddToNote
        assertEquals(1L, c.notebookId); assertEquals(10L, c.anchorId); assertEquals("نراجع العقد", c.text)
    }
    @Test fun addFuzzyTitle() {
        val c = p("ضيفي في نوتا شغلي تحت المهام: نراجع العقد") as Command.AddToNote
        assertEquals(1L, c.notebookId); assertEquals(10L, c.anchorId); assertEquals("نراجع العقد", c.text)
    }
    @Test fun addNewHeading() {
        val c = p("ضيف في نوتة الشغل تحت الاجتماعات حضر العرض") as Command.AddToNote
        assertEquals("الاجتماعات", c.newHeading); assertEquals("حضر العرض", c.text)
    }
    @Test fun addUnderItem() {
        val c = p("ضيف في نوتة الشغل تحت مراجعة الميزانية أرقام الربع التالت") as Command.AddToNote
        assertEquals(20L, c.anchorId); assertEquals("أرقام الربع التالت", c.text)
    }
    @Test fun unknownNotebook() {
        val c = p("ضيف في نوتة السفر شنطة الهدوم") as Command.NoteNotFound
        assertEquals("السفر", c.name); assertEquals("شنطة الهدوم", c.text)
    }
    @Test fun alarmStillWorksWithAddVerb() {
        assertTrue(p("حطلي منبه الساعة ٧ الصبح") is Command.Alarm)
    }

    @Test fun createWithItems() {
        val c = p("اعملي نوتة اسمها المشتريات وضيف جواها لبن وعيش") as Command.CreateNotebook
        assertEquals("المشتريات", c.title); assertNull(c.heading); assertEquals(listOf("لبن", "عيش"), c.items)
    }
    @Test fun createEmpty() {
        val c = p("اعملي نوتة جديدة اسمها رحلة الغردقة") as Command.CreateNotebook
        assertEquals("رحلة الغردقة", c.title); assertTrue(c.items.isEmpty())
    }
    @Test fun createWithHeading() {
        val c = p("افتحلي نوتة الكورس وحط فيها تحت عنوان المحاضرات المحاضرة الاولى") as Command.CreateNotebook
        assertEquals("الكورس", c.title); assertEquals("المحاضرات", c.heading); assertEquals(listOf("المحاضرة الاولى"), c.items)
    }
    @Test fun createWithComma() {
        val c = p("اعملي نوتة اسمها السفر، وضيف فيها الباسبور، الشاحن، النضارة") as Command.CreateNotebook
        assertEquals("السفر", c.title); assertEquals(listOf("الباسبور", "الشاحن", "النضارة"), c.items)
    }
    @Test fun splitTasks() {
        assertEquals(listOf("نراجع العقد", "اتصل بالمحامي"), CommandParser.splitItems("نراجع العقد واتصل بالمحامي"))
        assertEquals(listOf("اشتري ورق", "قلم"), CommandParser.splitItems("اشتري ورق وقلم"))
    }
    @Test fun addIntoExistingIsNotCreate() {
        assertTrue(p("ضيف في نوتة الشغل تحت المهام نراجع العقد") is Command.AddToNote)
    }

    @Test fun reminderKaman() {
        val c = p("فكرني كمان ساعة بالاجتماع") as Command.Reminder
        assertEquals("14/10 11:00", at(c.at)); assertEquals("الاجتماع", c.text)
    }
    @Test fun reminderKamanQuarter() {
        val c = p("فكرني كمان ربع ساعة بموضوع العربية") as Command.Reminder
        assertEquals("14/10 10:15", at(c.at)); assertEquals("موضوع العربية", c.text)
    }
    @Test fun reminderKamanMinutes() {
        val c = p("فكرني كمان ١٠ دقايق اطفي الفرن") as Command.Reminder
        assertEquals("14/10 10:10", at(c.at)); assertEquals("اطفي الفرن", c.text)
    }
    @Test fun reminderShwaya() {
        val c = p("فكرني كمان شوية اكلم ماما") as Command.Reminder
        assertEquals("14/10 10:30", at(c.at)); assertEquals("اكلم ماما", c.text)
    }
    @Test fun reminderKeepsBWord() {
        val c = p("فكرني كمان ساعة بيت خالتي") as Command.Reminder
        assertEquals("بيت خالتي", c.text)
    }
}
