package de.uekala.kehraus.core

import java.io.File

data class Move(val from: String, val to: String)

/**
 * Sortiert lose Dateien im Download-Ordner nach Art in Unterordner. Gelöscht wird dabei nichts,
 * und jede Verschiebung steht in einer Liste, mit der sich die Sortierung zurücknehmen lässt.
 */
object DownloadSorter {
    const val BASE = "Download"

    /** Unterordner je Dateiart. Was in keine Art passt, bleibt liegen. */
    val BUCKETS: Map<Category, String> = mapOf(
        Category.DOCUMENT to "Dokumente",
        Category.IMAGE to "Bilder",
        Category.VIDEO to "Videos",
        Category.AUDIO to "Audio",
        Category.ARCHIVE to "Archive",
        Category.APK to "Installationsdateien",
    )

    /**
     * Was einsortiert würde: Dateien direkt im Download-Ordner, die seit [minAgeDays] Tagen nicht
     * geändert wurden. Frische Downloads bleiben liegen, weil die App, die sie geladen hat, sie
     * dort noch erwartet.
     */
    fun plan(root: File, now: Long, minAgeDays: Int = 7, protection: Protection = Protection()): List<Move> {
        val dir = File(root, BASE)
        if (!dir.isDirectory || protection.coversFolder(dir)) return emptyList()
        val cutoff = now - minAgeDays.coerceAtLeast(0) * Rules.DAY_MS
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") && '\t' !in it.name && '\n' !in it.name }
            .filter { it.lastModified() in 1..cutoff && it.absolutePath !in protection.files }
            .mapNotNull { f ->
                val bucket = BUCKETS[Category.of(f.name)] ?: return@mapNotNull null
                Move(f.absolutePath, File(File(dir, bucket), f.name).absolutePath)
            }
            .sortedBy { it.from }
    }

    /** Führt die Verschiebungen aus und hängt sie an [journal] an. Liefert, was wirklich verschoben wurde. */
    fun apply(moves: List<Move>, journal: File): List<Move> {
        val done = ArrayList<Move>()
        for (m in moves) {
            val from = File(m.from)
            if (!from.isFile) continue
            val target = free(File(m.to))
            target.parentFile?.mkdirs()
            if (from.renameTo(target)) done.add(Move(m.from, target.absolutePath))
        }
        if (done.isNotEmpty()) {
            journal.parentFile?.mkdirs()
            journal.appendText(done.joinToString("") { "${it.from}\t${it.to}\n" }, Charsets.UTF_8)
        }
        return done
    }

    /** Wie viele Verschiebungen sich zurücknehmen lassen. */
    fun pending(journal: File): Int = read(journal).count { File(it.to).isFile }

    /**
     * Legt alles zurück, was noch an seinem einsortierten Platz liegt. Dateien, die inzwischen
     * weiterbewegt oder gelöscht wurden, werden übersprungen. Liefert die zurückgelegten Verschiebungen.
     */
    fun undo(journal: File): List<Move> {
        val undone = ArrayList<Move>()
        for (m in read(journal).asReversed()) {
            val now = File(m.to)
            val back = File(m.from)
            if (!now.isFile || back.exists()) continue
            if (now.renameTo(back)) {
                undone.add(m)
                now.parentFile?.let { if (it.list()?.isEmpty() == true) it.delete() }
            }
        }
        journal.delete()
        return undone
    }

    private fun read(journal: File): List<Move> =
        if (!journal.isFile) emptyList()
        else journal.readLines(Charsets.UTF_8).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 2) Move(p[0], p[1]) else null
        }

    private fun free(wanted: File): File {
        if (!wanted.exists()) return wanted
        val ext = wanted.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = File(wanted.parentFile, "${wanted.nameWithoutExtension} ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
