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

object Notifier {
    private const val CHANNEL = "ergebnis"

    /** Meldet einen Hintergrundlauf – aber nur, wenn wirklich etwas passiert ist. */
    fun report(ctx: Context, r: RunResult) {
        if (r.moved == 0 && r.failed == 0 && r.purged.count == 0) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ergebnis des Aufräumens", NotificationManager.IMPORTANCE_LOW)
        )
        val parts = ArrayList<String>()
        if (r.moved > 0) parts += "${files(r.moved)} (${Formatter.formatShortFileSize(ctx, r.movedBytes)}) in den Papierkorb verschoben"
        if (r.purged.count > 0) parts += "${Formatter.formatShortFileSize(ctx, r.purged.bytes)} endgültig freigegeben"
        if (r.failed > 0) parts += "${files(r.failed)} ließen sich nicht verschieben"
        val text = parts.joinToString(" · ")

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            1,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Kehraus hat aufgeräumt")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun files(n: Int) = if (n == 1) "1 Datei" else "$n Dateien"
}
