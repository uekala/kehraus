package de.uekala.kehraus.data

import android.content.Context
import android.content.SharedPreferences
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.RunResult

data class LastRun(val at: Long, val moved: Int, val bytes: Long, val failed: Int)

/** Einstellungen. Der Papierkorb selbst liegt nicht hier, sondern im Gerätespeicher. */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("kehraus", Context.MODE_PRIVATE)

    fun rule(ctx: Context, id: RuleId): RuleConfig {
        val p = sp(ctx)
        return RuleConfig(
            id = id,
            enabled = p.getBoolean("rule.${id.name}.on", false),
            ageDays = p.getInt("rule.${id.name}.age", id.defaultAgeDays),
            minSizeMb = p.getInt("rule.${id.name}.size", id.defaultMinSizeMb),
        )
    }

    fun rules(ctx: Context): List<RuleConfig> = RuleId.entries.map { rule(ctx, it) }

    fun setRule(ctx: Context, c: RuleConfig) {
        sp(ctx).edit()
            .putBoolean("rule.${c.id.name}.on", c.enabled)
            .putInt("rule.${c.id.name}.age", c.ageDays)
            .putInt("rule.${c.id.name}.size", c.minSizeMb)
            .apply()
    }

    fun retentionDays(ctx: Context): Int = sp(ctx).getInt("retention", 30)
    fun setRetentionDays(ctx: Context, days: Int) = sp(ctx).edit().putInt("retention", days).apply()

    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()

    fun notify(ctx: Context): Boolean = sp(ctx).getBoolean("notify", true)
    fun setNotify(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notify", v).apply()

    /** Summe aller endgültig gelöschten Bytes. */
    fun totalFreed(ctx: Context): Long = sp(ctx).getLong("freed", 0)
    fun addFreed(ctx: Context, bytes: Long) {
        if (bytes > 0) sp(ctx).edit().putLong("freed", totalFreed(ctx) + bytes).apply()
    }

    fun lastRun(ctx: Context): LastRun? {
        val p = sp(ctx)
        val at = p.getLong("last.at", 0)
        if (at == 0L) return null
        return LastRun(at, p.getInt("last.moved", 0), p.getLong("last.bytes", 0), p.getInt("last.failed", 0))
    }

    fun recordRun(ctx: Context, r: RunResult) {
        sp(ctx).edit()
            .putLong("last.at", r.at)
            .putInt("last.moved", r.moved)
            .putLong("last.bytes", r.movedBytes)
            .putInt("last.failed", r.failed)
            .putLong("freed", totalFreed(ctx) + r.purged.bytes)
            .apply()
    }
}
