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

    /** Meldet, was der tägliche Lauf einsortiert hat – nur wenn wirklich etwas passiert ist. */
    fun report(ctx: Context, r: RunResult) {
        val parts = ArrayList<String>()
        if (r.sorted > 0) parts += "${files(r.sorted)} einsortiert"
        if (r.emptyDirs > 0) parts += "${r.emptyDirs} leere Ordner entfernt"
        if (parts.isEmpty()) return
        show(ctx, 1, "Kehraus hat sortiert", parts.joinToString(" · "))
    }

    /** Empfehlung, etwas wegzuräumen. Verschoben wird erst nach Bestätigung in der App. */
    fun recommend(ctx: Context, r: RunResult) {
        val n = r.recommendations.sumOf { it.count }
        show(
            ctx, 4, "Kehraus empfiehlt aufzuräumen",
            "Deine Regeln finden ${files(n)} (${size(ctx, r.recommendedBytes)}), die weg könnten. " +
                "Unter „Regeln“ siehst du, was es ist – verschoben wird nur, was du bestätigst.",
        )
    }

    /** Erinnerung, dass im Papierkorb viel liegt. Er löscht nichts von selbst. */
    fun trashHint(ctx: Context, trashBytes: Long) {
        show(
            ctx, 5, "Papierkorb leeren?",
            "Im Papierkorb liegen ${size(ctx, trashBytes)}. Er löscht nichts von selbst – erst Leeren gibt den Platz frei.",
        )
    }

    /** Hinweis, wenn der Speicher knapp ist und der Papierkorb Platz hergeben könnte. */
    fun lowStorage(ctx: Context, info: StorageInfo, trashBytes: Long) {
        if (!info.low || trashBytes <= 0) return
        show(
            ctx, 2, "Speicher wird knapp",
            "Nur noch ${size(ctx, info.free)} frei. Im Papierkorb liegen ${size(ctx, trashBytes)} – leeren schafft sofort Platz.",
        )
    }

    /** Erinnerung, wenn sich im Cache der Apps viel angesammelt hat. Leeren kann das nur der Nutzer. */
    fun cacheHint(ctx: Context, cacheBytes: Long) {
        show(
            ctx, 3, "Viel App-Cache",
            "Im Cache der Apps liegen ${size(ctx, cacheBytes)}. Im Reiter „Apps“ steht, wo sich das Leeren am meisten lohnt.",
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
