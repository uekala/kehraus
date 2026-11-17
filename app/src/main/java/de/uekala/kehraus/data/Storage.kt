package de.uekala.kehraus.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import de.uekala.kehraus.core.Engine
import de.uekala.kehraus.core.History
import de.uekala.kehraus.core.Trash
import java.io.File

data class StorageInfo(val free: Long, val total: Long) {
    /** Weniger als ein Zehntel frei. */
    val low: Boolean get() = total > 0 && free < total / 10
}

/** Zugang zum Gerätespeicher. */
object Storage {
    val root: File get() = Environment.getExternalStorageDirectory()

    fun trash(): Trash = Trash(File(root, "Kehraus/Papierkorb"))
    fun engine(): Engine = Engine(root, trash())

    fun history(ctx: Context): History = History(File(ctx.filesDir, "verlauf.tsv"))

    /** Merkt sich, was beim Sortieren der Downloads wohin verschoben wurde. */
    fun sortJournal(ctx: Context): File = File(ctx.filesDir, "sortierung.tsv")

    /** Pfad ohne den Gerätespeicher davor, zur Anzeige. */
    fun relative(path: String): String = path.removePrefix(root.absolutePath).trimStart('/').ifEmpty { "Gerätespeicher" }

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Öffnet die Systemseite „Zugriff auf alle Dateien" für diese App. */
    fun openAccessSettings(ctx: Context) {
        val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { ctx.startActivity(forApp) }.onFailure { runCatching { ctx.startActivity(general) } }
    }

    fun info(): StorageInfo? = runCatching {
        val s = StatFs(root.path)
        StorageInfo(s.availableBytes, s.totalBytes)
    }.getOrNull()

    /** Sagt der Mediathek des Geräts, dass sich an diesen Pfaden etwas geändert hat. */
    fun rescan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(ctx.applicationContext, paths.toTypedArray(), null, null) }
    }
}
