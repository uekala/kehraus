package de.uekala.kehraus.bg

import android.content.Context
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.RunOptions
import de.uekala.kehraus.core.RunResult
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
    )

    /** Der tägliche Lauf und „Jetzt sortieren": sortieren und Empfehlungen zählen. Verschiebt nichts in den Papierkorb. */
    fun run(ctx: Context): RunResult? {
        if (!Storage.hasAccess()) return null
        val result = Storage.engine().run(Prefs.rules(ctx), options(ctx), System.currentTimeMillis())
        Prefs.recordRun(ctx, result)
        runCatching { Storage.history(ctx).record(result) }
        Storage.rescan(ctx, result.changedPaths)
        return result
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
