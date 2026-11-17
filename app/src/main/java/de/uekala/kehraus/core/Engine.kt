package de.uekala.kehraus.core

import java.io.File

data class RuleResult(
    val id: RuleId,
    val moved: Int,
    val bytes: Long,
    val failed: Int,
    /** Notbremse: Die Regel hätte zu viel getroffen und wurde deshalb nicht ausgeführt. */
    val held: Boolean = false,
    val heldCount: Int = 0,
    val heldBytes: Long = 0,
)

data class RunResult(
    val at: Long,
    val rules: List<RuleResult>,
    /** Was in diesem Lauf nach Ablauf der Frist endgültig gelöscht wurde. */
    val purged: Freed,
    /** Entfernte leere Ordner. */
    val emptyDirs: Int,
    /** Im Download-Ordner einsortierte Dateien. */
    val sorted: Int = 0,
    /** Pfade, an denen jetzt keine Datei mehr liegt (für die Mediathek des Geräts). */
    val changedPaths: List<String>,
) {
    val moved: Int get() = rules.sumOf { it.moved }
    val movedBytes: Long get() = rules.sumOf { it.bytes }
    val failed: Int get() = rules.sumOf { it.failed }
    val held: List<RuleResult> get() = rules.filter { it.held }
}

/** Ab dieser Menge führt ein Lauf eine Regel nicht von selbst aus, sondern wartet auf eine Bestätigung. */
data class Brake(val maxFiles: Int = 500, val maxBytes: Long = 5L * 1024 * Rules.MB) {
    fun trips(hits: List<Hit>): Boolean = hits.size > maxFiles || hits.sumOf { it.size } > maxBytes
}

data class RunOptions(
    val retentionDays: Int = 30,
    val protection: Protection = Protection(),
    val brake: Brake = Brake(),
    /** Regeln, für die die Notbremse in diesem Lauf nicht gilt (vom Nutzer bestätigt). */
    val confirmed: Set<RuleId> = emptySet(),
    val removeEmptyDirs: Boolean = false,
    /** Wenn gesetzt, werden Downloads nach Art einsortiert; die Datei merkt sich die Verschiebungen zum Zurücknehmen. */
    val sortJournal: File? = null,
    val sortAgeDays: Int = 7,
)

/** Ein Aufräum-Lauf: abgelaufenen Papierkorb leeren, jede eingeschaltete Regel anwenden, Reste entfernen. */
class Engine(private val root: File, val trash: Trash) {

    fun preview(rule: RuleConfig, now: Long, protection: Protection = Protection()): List<Hit> =
        if (rule.id == RuleId.DUPLICATES) duplicateCopies(protection)
        else Rules.find(root, rule, now, exclude = trash.dir, protection = protection)

    /** Die Kopien aller Duplikat-Gruppen; das jeweilige Original ist nicht dabei. */
    private fun duplicateCopies(protection: Protection): List<Hit> {
        val report = Analysis.scan(root, trash.dir)
        return Duplicates.find(report.files, report.root, protection)
            .flatMap { it.copies }
            .map { Hit(File(it.path), it.size, it.modified) }
    }

    fun run(rules: List<RuleConfig>, options: RunOptions, now: Long): RunResult {
        val purged = trash.purge(now, options.retentionDays)
        val changed = ArrayList<String>()
        val results = rules.filter { it.enabled }.map { rule ->
            val hits = preview(rule, now, options.protection)
            if (rule.id !in options.confirmed && options.brake.trips(hits)) {
                return@map RuleResult(rule.id, 0, 0, 0, held = true, heldCount = hits.size, heldBytes = hits.sumOf { it.size })
            }
            var moved = 0
            var bytes = 0L
            var failed = 0
            for (hit in hits) {
                if (trash.add(hit.file, rule.id.name, now) != null) {
                    moved++
                    bytes += hit.size
                    changed.add(hit.file.absolutePath)
                } else {
                    failed++
                }
            }
            RuleResult(rule.id, moved, bytes, failed)
        }
        var sorted = 0
        if (options.sortJournal != null) {
            val moves = DownloadSorter.plan(root, now, options.sortAgeDays, options.protection)
            val done = DownloadSorter.apply(moves, options.sortJournal)
            sorted = done.size
            done.forEach { changed.add(it.from); changed.add(it.to) }
        }
        val emptied = if (options.removeEmptyDirs) EmptyDirs.remove(root, trash.dir, options.protection) else 0
        return RunResult(now, results, purged, emptied, sorted, changed)
    }
}

/** Entfernt leere Ordner in den üblichen Ablageorten. Die Ablageorte selbst und bekannte Systemordner bleiben. */
object EmptyDirs {
    val BASES = listOf("Download", "Pictures", "DCIM", "Movies", "Documents", "Music")
    private val KEEP = setOf("DCIM/Camera", "Pictures/Screenshots")

    fun remove(root: File, exclude: File?, protection: Protection = Protection()): Int {
        val keep = KEEP.map { File(root, it).absolutePath }.toSet()
        var removed = 0

        /** Liefert true, wenn [dir] nach dem Aufräumen leer und entfernt ist. */
        fun sweep(dir: File, isBase: Boolean): Boolean {
            if (dir.absoluteFile == exclude?.absoluteFile) return false
            if (!isBase && (dir.name.startsWith(".") || dir.absolutePath in protection.folders)) return false
            val children = dir.listFiles() ?: return false
            var empty = true
            for (c in children) {
                if (!c.isDirectory || !sweep(c, false)) empty = false
            }
            if (!empty || isBase || dir.absolutePath in keep) return false
            if (!dir.delete()) return false
            removed++
            return true
        }

        for (base in BASES) {
            val dir = File(root, base)
            if (dir.isDirectory && !protection.coversFolder(dir)) sweep(dir, true)
        }
        return removed
    }
}
