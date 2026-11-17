package de.uekala.kehraus.core

/** Platzbedarf und letzte Nutzung einer installierten App. */
data class AppUsage(
    val pkg: String,
    val label: String,
    /** Die App selbst (Installationspaket). */
    val appBytes: Long,
    /** Daten der App, der Cache ist darin enthalten. */
    val dataBytes: Long,
    val cacheBytes: Long,
    /** Zeitpunkt der letzten Nutzung; 0, wenn das System keine Nutzung aufgezeichnet hat. */
    val lastUsed: Long,
    val system: Boolean,
) {
    val total: Long get() = appBytes + dataBytes

    /** Tage seit der letzten Nutzung; null, wenn keine Nutzung aufgezeichnet ist. */
    fun idleDays(now: Long): Int? =
        if (lastUsed <= 0) null else ((now - lastUsed) / Rules.DAY_MS).toInt().coerceAtLeast(0)
}

/** Auswertungen über die App-Liste. Hier wird nur sortiert und vorgeschlagen, nie etwas verändert. */
object AppAdvice {
    fun bySize(apps: List<AppUsage>, includeSystem: Boolean): List<AppUsage> =
        apps.filter { includeSystem || !it.system }.sortedByDescending { it.total }

    fun byCache(apps: List<AppUsage>, minBytes: Long = 20 * Rules.MB): List<AppUsage> =
        apps.filter { it.cacheBytes >= minBytes }.sortedByDescending { it.cacheBytes }

    /**
     * Selbst installierte Apps, die Platz belegen und nachweislich seit [minDays] Tagen nicht
     * geöffnet wurden. Vorinstallierte Apps bleiben außen vor: Sie lassen sich ohnehin nicht entfernen.
     */
    fun unused(
        apps: List<AppUsage>,
        now: Long,
        self: String,
        minDays: Int = 60,
        minBytes: Long = 50 * Rules.MB,
    ): List<AppUsage> = apps.filter { app ->
        val idle = app.idleDays(now)
        !app.system && app.pkg != self && app.total >= minBytes && idle != null && idle >= minDays
    }.sortedByDescending { it.total }

    /**
     * Selbst installierte Apps, für die das System gar keine Nutzung kennt. Das kann „nie geöffnet"
     * heißen – oder ein Dienst sein, der nie im Vordergrund läuft. Deshalb getrennt von [unused].
     */
    fun neverSeen(
        apps: List<AppUsage>,
        self: String,
        minBytes: Long = 50 * Rules.MB,
    ): List<AppUsage> = apps.filter { !it.system && it.pkg != self && it.total >= minBytes && it.lastUsed <= 0 }
        .sortedByDescending { it.total }
}
