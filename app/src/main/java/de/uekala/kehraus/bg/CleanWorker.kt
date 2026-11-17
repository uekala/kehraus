package de.uekala.kehraus.bg

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.uekala.kehraus.core.Rules
import de.uekala.kehraus.data.Apps
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Der tägliche Lauf im Hintergrund. */
class CleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!Prefs.auto(ctx)) return@withContext Result.success()
        val result = try {
            Runner.run(ctx)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        if (result != null && Prefs.notify(ctx)) {
            Notifier.report(ctx, result)
            runCatching {
                val info = Storage.info()
                if (info != null) Notifier.lowStorage(ctx, info, Storage.trash().entries().sumOf { it.size })
            }
        }
        if (Prefs.notify(ctx)) runCatching { remindCache(ctx) }
        Result.success()
    }

    /** Höchstens einmal pro Woche, und nur wenn wirklich viel zusammengekommen ist. */
    private fun remindCache(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!Apps.hasUsageAccess(ctx) || now - Prefs.cacheHintAt(ctx) < 7 * Rules.DAY_MS) return
        val cache = Apps.load(ctx).sumOf { it.cacheBytes }
        if (cache < CACHE_HINT_BYTES) return
        Notifier.cacheHint(ctx, cache)
        Prefs.setCacheHintAt(ctx, now)
    }

    companion object {
        private const val CACHE_HINT_BYTES = 5L * 1024 * 1024 * 1024

        private const val NAME = "kehraus-taeglich"

        /** Stellt den täglichen Lauf ein oder ab, je nach Einstellung. */
        fun sync(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!Prefs.auto(ctx)) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CleanWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
