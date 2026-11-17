package de.uekala.kehraus.bg

import android.content.Context
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.RunOptions
import de.uekala.kehraus.core.RunResult
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage

/** Ein Aufräum-Lauf mit den gespeicherten Einstellungen – für den Knopf in der App und den täglichen Lauf. */
object Runner {
    /** [confirmed]: Regeln, die trotz großer Menge laufen dürfen, weil der Nutzer es bestätigt hat. */
    fun run(ctx: Context, confirmed: Set<RuleId> = emptySet()): RunResult? {
        if (!Storage.hasAccess()) return null
        val options = RunOptions(
            retentionDays = Prefs.retentionDays(ctx),
            protection = Prefs.protection(ctx),
            confirmed = confirmed,
            removeEmptyDirs = Prefs.emptyDirs(ctx),
            sortJournal = if (Prefs.sortDownloads(ctx)) Storage.sortJournal(ctx) else null,
        )
        val result = Storage.engine().run(Prefs.rules(ctx), options, System.currentTimeMillis())
        Prefs.recordRun(ctx, result)
        runCatching { Storage.history(ctx).record(result) }
        Storage.rescan(ctx, result.changedPaths)
        return result
    }
}
