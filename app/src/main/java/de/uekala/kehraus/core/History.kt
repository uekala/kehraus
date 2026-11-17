package de.uekala.kehraus.core

import java.io.File

/** Eine Zeile im Verlauf: was eine Regel (oder das Leeren nach Frist) in einem Lauf getan hat. */
data class HistoryEntry(
    val at: Long,
    /** [RuleId.name], [PURGED] oder [EMPTY_DIRS]. */
    val what: String,
    val count: Int,
    val bytes: Long,
    val failed: Int = 0,
    val held: Boolean = false,
) {
    companion object {
        const val PURGED = "PURGED"
        const val EMPTY_DIRS = "EMPTY_DIRS"
    }
}

/** Verlauf der Läufe als kleine Textdatei, neueste Einträge am Ende. Es bleiben die letzten [keep] Zeilen. */
class History(private val file: File, private val keep: Int = 300) {

    /** Neueste zuerst. */
    fun entries(): List<HistoryEntry> = synchronized(LOCK) { load().asReversed() }

    /** Hält fest, was ein Lauf getan hat. Läufe, in denen nichts passiert ist, hinterlassen keine Zeile. */
    fun record(r: RunResult) {
        val fresh = ArrayList<HistoryEntry>()
        if (r.purged.count > 0) fresh += HistoryEntry(r.at, HistoryEntry.PURGED, r.purged.count, r.purged.bytes)
        for (x in r.rules) {
            if (x.held) fresh += HistoryEntry(r.at, x.id.name, x.heldCount, x.heldBytes, held = true)
            else if (x.moved > 0 || x.failed > 0) fresh += HistoryEntry(r.at, x.id.name, x.moved, x.bytes, x.failed)
        }
        if (r.emptyDirs > 0) fresh += HistoryEntry(r.at, HistoryEntry.EMPTY_DIRS, r.emptyDirs, 0)
        if (fresh.isEmpty()) return
        synchronized(LOCK) {
            val all = (load() + fresh).takeLast(keep)
            file.parentFile?.mkdirs()
            file.writeText(all.joinToString("") { format(it) + "\n" }, Charsets.UTF_8)
        }
    }

    private fun load(): List<HistoryEntry> =
        if (file.isFile) file.readLines(Charsets.UTF_8).mapNotNull(::parse) else emptyList()

    companion object {
        private val LOCK = Any()

        internal fun format(e: HistoryEntry): String =
            listOf(e.at, e.what, e.count, e.bytes, e.failed, if (e.held) 1 else 0).joinToString("\t")

        internal fun parse(line: String): HistoryEntry? {
            val p = line.split('\t')
            if (p.size != 6) return null
            return HistoryEntry(
                at = p[0].toLongOrNull() ?: return null,
                what = p[1],
                count = p[2].toIntOrNull() ?: return null,
                bytes = p[3].toLongOrNull() ?: return null,
                failed = p[4].toIntOrNull() ?: return null,
                held = p[5] == "1",
            )
        }
    }
}
