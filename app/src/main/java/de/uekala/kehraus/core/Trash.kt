package de.uekala.kehraus.core

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class TrashEntry(
    val id: String,
    /** Voller Pfad, an dem die Datei vor dem Verschieben lag. */
    val original: String,
    val trashedAt: Long,
    val size: Long,
    /** Name der Regel ([RuleId.name]), die die Datei getroffen hat. */
    val rule: String,
) {
    val name: String get() = original.substringAfterLast('/')
    fun expiresAt(retentionDays: Int): Long = trashedAt + retentionDays.coerceAtLeast(1) * Rules.DAY_MS
}

data class Freed(val count: Int, val bytes: Long)

/**
 * Der Papierkorb: ein Ordner mit den verschobenen Dateien und einer Liste, wo sie herkamen.
 *
 * Die Liste (`index.tsv`) liegt bewusst neben den Dateien im Gerätespeicher und nicht in den
 * App-Daten. So bleibt alles wiederherstellbar, auch wenn die App neu installiert wird.
 * Eine Zeile je Datei: Kennung, Zeitpunkt, Größe, Regel, ursprünglicher Pfad (durch Tab getrennt).
 */
class Trash(val dir: File) {
    private val filesDir = File(dir, "files")
    private val index = File(dir, "index.tsv")

    fun fileOf(e: TrashEntry): File = File(filesDir, storedName(e.id, e.original))

    /** Alle Einträge, deren Datei wirklich im Papierkorb liegt. Neueste zuerst, bei gleichem Zeitpunkt nach Name. */
    fun entries(): List<TrashEntry> = synchronized(LOCK) {
        load().sortedWith(compareByDescending<TrashEntry> { it.trashedAt }.thenBy { it.name.lowercase() })
    }

    /**
     * Verschiebt [file] in den Papierkorb. Liefert null, wenn das nicht geklappt hat –
     * die Datei bleibt dann unverändert an ihrem Platz.
     */
    fun add(file: File, rule: String, now: Long): TrashEntry? = synchronized(LOCK) {
        if (!file.isFile) return null
        if (!filesDir.isDirectory && !filesDir.mkdirs()) return null
        File(dir, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }

        val entry = TrashEntry(UUID.randomUUID().toString(), file.absolutePath, now, file.length(), rule)
        // Erst den Eintrag schreiben, dann verschieben: Bricht der Vorgang dazwischen ab, gibt es
        // höchstens einen Eintrag ohne Datei (wird beim Lesen aussortiert), nie eine Datei ohne Herkunft.
        try {
            FileOutputStream(index, true).use { out ->
                out.write((format(entry) + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: Exception) {
            return null
        }
        if (file.renameTo(fileOf(entry))) entry else null
    }

    /** Legt die Datei zurück an ihren alten Platz. Liefert den Zielpfad oder null bei Fehler. */
    fun restore(id: String): File? = synchronized(LOCK) {
        val all = load()
        val entry = all.firstOrNull { it.id == id } ?: return null
        val target = freeTarget(File(entry.original))
        target.parentFile?.mkdirs()
        if (!fileOf(entry).renameTo(target)) return null
        save(all.filter { it.id != id })
        target
    }

    /** Löscht die genannten Einträge endgültig. */
    fun delete(ids: Collection<String>): Freed = synchronized(LOCK) {
        if (ids.isEmpty()) return Freed(0, 0)
        val wanted = ids.toSet()
        val all = load()
        val gone = HashSet<String>()
        var bytes = 0L
        for (e in all) {
            if (e.id !in wanted) continue
            val f = fileOf(e)
            if (f.delete() || !f.exists()) {
                gone.add(e.id)
                bytes += e.size
            }
        }
        if (gone.isNotEmpty()) save(all.filter { it.id !in gone })
        Freed(gone.size, bytes)
    }

    /** Löscht alles endgültig, dessen Frist zum Zeitpunkt [now] abgelaufen ist. */
    fun purge(now: Long, retentionDays: Int): Freed = synchronized(LOCK) {
        delete(load().filter { it.expiresAt(retentionDays) <= now }.map { it.id })
    }

    private fun load(): List<TrashEntry> {
        if (!index.isFile) return emptyList()
        return index.readLines(Charsets.UTF_8).mapNotNull(::parse).filter { fileOf(it).isFile }
    }

    private fun save(entries: List<TrashEntry>) {
        val tmp = File(dir, "index.tsv.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(entries.joinToString("") { format(it) + "\n" }.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(index)) throw java.io.IOException("Papierkorb-Liste konnte nicht geschrieben werden")
    }

    companion object {
        /** Ein Schloss für alle Instanzen: App und Hintergrundlauf dürfen sich nicht in die Quere kommen. */
        private val LOCK = Any()

        private fun storedName(id: String, original: String): String {
            val ext = original.substringAfterLast('/').substringAfterLast('.', "")
            return if (ext.length in 1..8 && ext.all { it.isLetterOrDigit() }) "$id.$ext" else id
        }

        /** Liegt am alten Platz inzwischen etwas anderes, bekommt die Datei einen Zusatz im Namen. */
        private fun freeTarget(wanted: File): File {
            if (!wanted.exists()) return wanted
            val base = wanted.nameWithoutExtension
            val ext = wanted.extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (true) {
                val suffix = if (n == 1) " (wiederhergestellt)" else " (wiederhergestellt $n)"
                val candidate = File(wanted.parentFile, base + suffix + ext)
                if (!candidate.exists()) return candidate
                n++
            }
        }

        internal fun format(e: TrashEntry): String =
            listOf(e.id, e.trashedAt.toString(), e.size.toString(), e.rule, escape(e.original)).joinToString("\t")

        internal fun parse(line: String): TrashEntry? {
            val p = line.split('\t')
            if (p.size != 5) return null
            val at = p[1].toLongOrNull() ?: return null
            val size = p[2].toLongOrNull() ?: return null
            return TrashEntry(p[0], unescape(p[4]), at, size, p[3])
        }

        private fun escape(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }

        private fun unescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
