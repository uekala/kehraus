package de.uekala.kehraus

import android.app.Application
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uekala.kehraus.bg.CleanWorker
import de.uekala.kehraus.bg.Runner
import de.uekala.kehraus.core.Hit
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.TrashEntry
import de.uekala.kehraus.data.LastRun
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage
import de.uekala.kehraus.data.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Was eine Regel im Moment treffen würde. [hits] enthält höchstens die 200 größten Dateien. */
data class Preview(val count: Int, val bytes: Long, val hits: List<Hit>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var hasAccess by mutableStateOf(Storage.hasAccess()); private set
    var rules by mutableStateOf(Prefs.rules(app)); private set
    var previews by mutableStateOf<Map<RuleId, Preview>>(emptyMap()); private set
    var trash by mutableStateOf<List<TrashEntry>>(emptyList()); private set
    var storage by mutableStateOf<StorageInfo?>(null); private set
    var retentionDays by mutableStateOf(Prefs.retentionDays(app)); private set
    var auto by mutableStateOf(Prefs.auto(app)); private set
    var notify by mutableStateOf(Prefs.notify(app)); private set
    var lastRun by mutableStateOf<LastRun?>(Prefs.lastRun(app)); private set
    var totalFreed by mutableStateOf(Prefs.totalFreed(app)); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    val trashBytes: Long get() = trash.sumOf { it.size }

    fun refresh() {
        hasAccess = Storage.hasAccess()
        lastRun = Prefs.lastRun(ctx)
        totalFreed = Prefs.totalFreed(ctx)
        if (!hasAccess) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val current = rules
            val (t, s, p) = withContext(Dispatchers.IO) {
                val engine = Storage.engine()
                Triple(
                    runCatching { engine.trash.entries() }.getOrDefault(emptyList()),
                    Storage.info(),
                    current.associate { it.id to preview(it, now) },
                )
            }
            trash = t
            storage = s
            previews = p
        }
    }

    private fun preview(rule: RuleConfig, now: Long): Preview {
        val hits = runCatching { Storage.engine().preview(rule, now) }.getOrDefault(emptyList())
        return Preview(hits.size, hits.sumOf { it.size }, hits.sortedByDescending { it.size }.take(200))
    }

    fun setRule(c: RuleConfig) {
        Prefs.setRule(ctx, c)
        rules = Prefs.rules(ctx)
        if (!hasAccess) return
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { preview(c, System.currentTimeMillis()) }
            previews = previews + (c.id to p)
        }
    }

    fun runNow() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Runner.run(ctx) } }
            busy = false
            val r = result.getOrNull()
            message = when {
                result.isFailure -> "Aufräumen fehlgeschlagen: ${result.exceptionOrNull()?.message ?: "unbekannter Fehler"}"
                r == null -> "Erst den Dateizugriff erlauben"
                r.moved == 0 && r.failed == 0 -> "Nichts zu tun"
                else -> buildString {
                    append("${files(r.moved)} (${size(r.movedBytes)}) in den Papierkorb verschoben")
                    if (r.failed > 0) append(" · ${files(r.failed)} ließen sich nicht verschieben")
                }
            }
            refresh()
        }
    }

    fun restore(ids: List<String>) = trashAction {
        val restored = ids.mapNotNull { Storage.trash().restore(it) }
        Storage.rescan(ctx, restored.map { it.absolutePath })
        if (restored.size == ids.size) "${files(restored.size)} wiederhergestellt"
        else "${restored.size} von ${ids.size} wiederhergestellt"
    }

    fun delete(ids: List<String>) = trashAction {
        val freed = Storage.trash().delete(ids)
        Prefs.addFreed(ctx, freed.bytes)
        "${files(freed.count)} endgültig gelöscht (${size(freed.bytes)})"
    }

    private fun trashAction(block: () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse { "Fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}" }
            }
            busy = false
            message = text
            refresh()
        }
    }

    fun updateRetention(days: Int) {
        retentionDays = days
        Prefs.setRetentionDays(ctx, days)
    }

    fun updateAuto(v: Boolean) {
        auto = v
        Prefs.setAuto(ctx, v)
        CleanWorker.sync(ctx)
    }

    fun updateNotify(v: Boolean) {
        notify = v
        Prefs.setNotify(ctx, v)
    }

    fun size(bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    companion object {
        fun files(n: Int): String = if (n == 1) "1 Datei" else "$n Dateien"
    }
}
