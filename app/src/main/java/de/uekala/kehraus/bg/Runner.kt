package de.uekala.kehraus.bg

import android.content.Context
import de.uekala.kehraus.core.RunResult
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage

/** Ein Aufräum-Lauf mit den gespeicherten Einstellungen – für den Knopf in der App und den täglichen Lauf. */
object Runner {
    fun run(ctx: Context): RunResult? {
        if (!Storage.hasAccess()) return null
        val result = Storage.engine().run(Prefs.rules(ctx), Prefs.retentionDays(ctx), System.currentTimeMillis())
        Prefs.recordRun(ctx, result)
        Storage.rescan(ctx, result.changedPaths)
        return result
    }
}
