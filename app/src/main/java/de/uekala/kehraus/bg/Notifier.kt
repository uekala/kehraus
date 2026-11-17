package de.uekala.kehraus.bg

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import de.uekala.kehraus.MainActivity
import de.uekala.kehraus.R
import de.uekala.kehraus.core.RunResult
import de.uekala.kehraus.data.StorageInfo

object Notifier {
    private const val CHANNEL = "ergebnis"

    /** Meldet einen Hintergrundlauf – aber nur, wenn wirklich etwas passiert ist oder eine Regel wartet. */
    fun report(ctx: Context, r: RunResult) {
        val parts = ArrayList<String>()
        if (r.moved > 0) parts += "${files(r.moved)} (${size(ctx, r.movedBytes)}) in den Papierkorb verschoben"
        if (r.purged.count > 0) parts += "${size(ctx, r.purged.bytes)} endgültig freigegeben"
        if (r.failed > 0) parts += "${files(r.failed)} ließen sich nicht verschieben"
        if (r.held.isNotEmpty()) {
            val n = r.held.sumOf { it.heldCount }
            parts += "${files(n)} warten auf deine Bestätigung, weil es ungewöhnlich viele sind"
        }
        if (parts.isEmpty()) return
        val title = if (r.moved == 0 && r.purged.count == 0 && r.held.isNotEmpty()) "Kehraus wartet auf dich" else "Kehraus hat aufgeräumt"
        show(ctx, 1, title, parts.joinToString(" · "))
    }

    /** Hinweis, wenn der Speicher knapp ist und der Papierkorb Platz hergeben könnte. */
    fun lowStorage(ctx: Context, info: StorageInfo, trashBytes: Long) {
        if (!info.low || trashBytes <= 0) return
        show(
            ctx, 2, "Speicher wird knapp",
            "Nur noch ${size(ctx, info.free)} frei. Im Papierkorb liegen ${size(ctx, trashBytes)} – leeren schafft sofort Platz.",
        )
    }

    private fun show(ctx: Context, id: Int, title: String, text: String) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ergebnis des Aufräumens", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun size(ctx: Context, bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)
    private fun files(n: Int) = if (n == 1) "1 Datei" else "$n Dateien"
}
