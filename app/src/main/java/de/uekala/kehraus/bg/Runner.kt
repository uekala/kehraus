package de.uekala.kehraus.bg

import android.content.Context
import de.uekala.kehraus.core.DocCache
import de.uekala.kehraus.core.DocClassifier
import de.uekala.kehraus.core.DocVerdict
import de.uekala.kehraus.core.Documents
import de.uekala.kehraus.core.Journal
import de.uekala.kehraus.core.Move
import de.uekala.kehraus.core.Protection
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.RunOptions
import de.uekala.kehraus.core.RunResult
import de.uekala.kehraus.data.Ocr
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage

object Runner {
    private fun options(ctx: Context, confirmed: Set<RuleId> = emptySet()) = RunOptions(
        // Der Papierkorb löscht nie von selbst.
        retentionDays = null,
        protection = Prefs.protection(ctx),
        confirmed = confirmed,
        removeEmptyDirs = Prefs.emptyDirs(ctx),
        sortJournal = if (Prefs.sortDownloads(ctx)) Storage.sortJournal(ctx) else null,
        screenshotJournal = if (Prefs.sortScreenshots(ctx)) Storage.screenshotJournal(ctx) else null,
    )

    /**
     * Der tägliche Lauf und „Jetzt sortieren": sortieren und Empfehlungen zählen. Verschiebt nichts in den
     * Papierkorb. [ocrBudgetMs] begrenzt, wie lange die Texterkennung in diesem Lauf Fotos prüfen darf.
     */
    fun run(ctx: Context, ocrBudgetMs: Long): RunResult? {
        if (!Storage.hasAccess()) return null
        val now = System.currentTimeMillis()
        val opts = options(ctx)
        var result = Storage.engine().run(Prefs.rules(ctx), opts, now)
        if (Prefs.sortDocs(ctx)) {
            val moved = runCatching { sortDocuments(ctx, now, opts.protection, ocrBudgetMs) }.getOrDefault(emptyList())
            if (moved.isNotEmpty()) {
                result = result.copy(
                    sorted = result.sorted + moved.size,
                    changedPaths = result.changedPaths + moved.flatMap { listOf(it.from, it.to) },
                )
            }
        }
        Prefs.recordRun(ctx, result)
        runCatching { Storage.history(ctx).record(result) }
        Storage.rescan(ctx, result.changedPaths)
        return result
    }

    /** Prüft noch unbekannte Fotos per Texterkennung und legt erkannte Dokumente in den Dokumente-Ordner. */
    private fun sortDocuments(ctx: Context, now: Long, protection: Protection, budgetMs: Long): List<Move> {
        val cache = DocCache(Storage.docCache(ctx))
        val todo = Documents.candidates(Storage.root, now, cache.load(), protection)
        val deadline = System.currentTimeMillis() + budgetMs
        val batch = ArrayList<DocVerdict>()
        var unreadable = 0
        for (f in todo) {
            if (System.currentTimeMillis() > deadline) break
            val text = Ocr.text(f)
            if (text == null) {
                // Nicht merken: Beim nächsten Lauf neu versuchen. Gehäufte Fehler heißen eher, dass die Erkennung klemmt.
                if (++unreadable >= 20) break
                continue
            }
            unreadable = 0
            batch += DocVerdict(f.absolutePath, f.lastModified(), DocClassifier.isDocument(text))
            if (batch.size >= 25) {
                cache.add(batch)
                batch.clear()
            }
        }
        cache.add(batch)
        return Journal.apply(Documents.plan(Storage.root, now, cache.load(), protection), Storage.docJournal(ctx))
    }

    /** Verschiebt die Treffer der genannten Regeln in den Papierkorb – nur nach Bestätigung durch den Nutzer. */
    fun trash(ctx: Context, ids: Set<RuleId>): RunResult? {
        if (!Storage.hasAccess() || ids.isEmpty()) return null
        val rules = Prefs.rules(ctx).filter { it.id in ids }
        val result = Storage.engine().trash(rules, options(ctx, confirmed = ids), System.currentTimeMillis())
        runCatching { Storage.history(ctx).record(result) }
        Storage.rescan(ctx, result.changedPaths)
        return result
    }
}
