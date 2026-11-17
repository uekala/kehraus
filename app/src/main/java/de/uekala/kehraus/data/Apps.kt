package de.uekala.kehraus.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import android.provider.Settings
import de.uekala.kehraus.core.AppUsage

/** Liest Größe und letzte Nutzung der installierten Apps aus. Verändern kann Kehraus fremde Apps nicht. */
object Apps {
    /** Alles davor ist kein echter Zeitpunkt, sondern „nie genutzt". */
    private const val SANE_SINCE = 1_420_070_400_000L // 1.1.2015
    private const val TWO_YEARS = 730L * 24 * 60 * 60 * 1000

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    /** Öffnet die Systemseite der App – dort gibt es „Cache leeren" und „Deinstallieren". */
    fun openDetails(ctx: Context, pkg: String) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    fun load(ctx: Context): List<AppUsage> {
        val pm = ctx.packageManager
        val storage = ctx.getSystemService(StorageStatsManager::class.java)
        val usage = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val lastUsed = runCatching {
            usage.queryAndAggregateUsageStats(now - TWO_YEARS, now).mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap())
        val user = Process.myUserHandle()

        return pm.getInstalledApplications(0).mapNotNull { info ->
            val stats = runCatching { storage.queryStatsForPackage(info.storageUuid, info.packageName, user) }.getOrNull()
                ?: return@mapNotNull null
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            AppUsage(
                pkg = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                appBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes,
                lastUsed = (lastUsed[info.packageName] ?: 0L).takeIf { it >= SANE_SINCE } ?: 0L,
                system = system,
            )
        }
    }
}
