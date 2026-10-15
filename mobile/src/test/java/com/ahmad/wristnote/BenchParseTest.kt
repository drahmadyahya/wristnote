package com.ahmad.wristnote

import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * Speech-model benchmark helper (runs only when BENCH_TSV is set):
 * parses every model's transcript and the reference sentence, and records whether
 * they give the same command (type, time, notebook, question).
 */
class BenchParseTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 14, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val books = listOf(
        NotebookRef(1, "الشغل", listOf(10L to "المهام", 11L to "أفكار"), listOf(20L to "مراجعة الميزانية")),
        NotebookRef(2, "المشتريات", emptyList()),
    )

    private fun at(c: Long): String {
        val k = Calendar.getInstance().apply { timeInMillis = c }
        return "%d/%d %02d:%02d".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))
    }

    /** The part of a command that must be right for it to work. */
    private fun key(c: Command?): String = when (c) {
        null -> "idea"
        is Command.Alarm -> "alarm ${at(c.at)}"
        is Command.Reminder -> "reminder ${at(c.at)}"
        is Command.Event -> "event ${at(c.at)} ${c.timed}"
        is Command.AddToNote -> "add nb${c.notebookId} h${c.anchorId}"
        is Command.CreateNotebook -> "create"
        is Command.Ask -> "ask ${c.what} ${c.notebookId} ${c.dayStart}"
        is Command.NoteNotFound -> "notfound"
    }

    private fun detail(c: Command?): String = when (c) {
        null -> ""
        is Command.Alarm -> c.label
        is Command.Reminder -> c.text
        is Command.Event -> c.title
        is Command.AddToNote -> c.text
        is Command.CreateNotebook -> c.title + " | " + c.items.joinToString(" / ")
        is Command.NoteNotFound -> c.name
        else -> ""
    }

    @Test fun bench() {
        val tsv = System.getenv("BENCH_TSV") ?: return
        val ref = File(System.getenv("BENCH_REF")).readLines().filter { it.contains('|') }
            .associate { it.substringBefore('|') to it.substringAfter('|') }
        val out = File(System.getenv("BENCH_OUT"))
        out.printWriter().use { w ->
            for (line in File(tsv).readLines()) {
                val p = line.split('\t')
                if (p.size < 4) continue
                val (model, clip, _, text) = p
                val r = CommandParser.parse(ref[clip.substringBefore('_')] ?: continue, books, now)
                val h = try { CommandParser.parse(text, books, now) } catch (e: Exception) { null }
                val ok = key(r) == key(h)
                w.println(listOf(model, clip, if (ok) "1" else "0", key(r), key(h), detail(r), detail(h)).joinToString("\t"))
            }
        }
    }
}
