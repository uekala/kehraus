package de.uekala.kehraus.core

import java.io.File

data class RuleResult(val id: RuleId, val moved: Int, val bytes: Long, val failed: Int)

data class RunResult(
    val at: Long,
    val rules: List<RuleResult>,
    /** Was in diesem Lauf nach Ablauf der Frist endgültig gelöscht wurde. */
    val purged: Freed,
    /** Pfade, an denen jetzt keine Datei mehr liegt (für die Mediathek des Geräts). */
    val changedPaths: List<String>,
) {
    val moved: Int get() = rules.sumOf { it.moved }
    val movedBytes: Long get() = rules.sumOf { it.bytes }
    val failed: Int get() = rules.sumOf { it.failed }
}

/** Ein Aufräum-Lauf: abgelaufenen Papierkorb leeren, dann jede eingeschaltete Regel anwenden. */
class Engine(private val root: File, val trash: Trash) {

    fun preview(rule: RuleConfig, now: Long): List<Hit> = Rules.find(root, rule, now, exclude = trash.dir)

    fun run(rules: List<RuleConfig>, retentionDays: Int, now: Long): RunResult {
        val purged = trash.purge(now, retentionDays)
        val changed = ArrayList<String>()
        val results = rules.filter { it.enabled }.map { rule ->
            var moved = 0
            var bytes = 0L
            var failed = 0
            for (hit in preview(rule, now)) {
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
        return RunResult(now, results, purged, changed)
    }
}
