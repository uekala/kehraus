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
import de.uekala.kehraus.core.Analysis
import de.uekala.kehraus.core.DupGroup
import de.uekala.kehraus.core.Duplicates
import de.uekala.kehraus.core.HistoryEntry
import de.uekala.kehraus.core.Hit
import de.uekala.kehraus.core.Protection
import de.uekala.kehraus.core.Report
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.RuleResult
import de.uekala.kehraus.core.TrashEntry
import de.uekala.kehraus.data.LastRun
import de.uekala.kehraus.data.Prefs
import de.uekala.kehraus.data.Storage
import de.uekala.kehraus.data.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Was eine Regel im Moment treffen würde. [hits] enthält höchstens die 200 größten Dateien. */
data class Preview(val count: Int, val bytes: Long, val hits: List<Hit>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var hasAccess by mutableStateOf(Storage.hasAccess()); private set
    var rules by mutableStateOf(Prefs.rules(app)); private set
    var previews by mutableStateOf<Map<RuleId, Preview>>(emptyMap()); private set
    var trash by mutableStateOf<List<TrashEntry>>(emptyList()); private set
    var history by mutableStateOf<List<HistoryEntry>>(emptyList()); private set
    var storage by mutableStateOf<StorageInfo?>(null); private set
    var protection by mutableStateOf(Prefs.protection(app)); private set
    var retentionDays by mutableStateOf(Prefs.retentionDays(app)); private set
    var auto by mutableStateOf(Prefs.auto(app)); private set
    var notify by mutableStateOf(Prefs.notify(app)); private set
    var emptyDirs by mutableStateOf(Prefs.emptyDirs(app)); private set
    var lastRun by mutableStateOf<LastRun?>(Prefs.lastRun(app)); private set
    var totalFreed by mutableStateOf(Prefs.totalFreed(app)); private set

    /** Regeln, die beim letzten Lauf wegen der Menge angehalten wurden. */
    var held by mutableStateOf(heldRules()); private set

    /** Gesetzt, solange die App fragt, ob angehaltene Regeln trotzdem laufen sollen. */
    var askHeld by mutableStateOf<List<RuleResult>>(emptyList()); private set

    /** Ergebnis der letzten Speicher-Analyse; null, solange noch keine gelaufen ist. */
    var report by mutableStateOf<Report?>(null); private set
    var duplicates by mutableStateOf<List<DupGroup>?>(null); private set

    /** Fortschrittstext, solange die Analyse läuft. */
    var analyzing by mutableStateOf<String?>(null); private set

    /** Pfade, die seit der Analyse in den Papierkorb gewandert sind – die Listen blenden sie aus. */
    var gone by mutableStateOf<Set<String>>(emptySet()); private set

    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    val trashBytes: Long get() = trash.sumOf { it.size }

    private fun heldRules(): List<RuleId> {
        val names = Prefs.held(ctx)
        return RuleId.entries.filter { it.name in names }
    }

    fun refresh() {
        hasAccess = Storage.hasAccess()
        lastRun = Prefs.lastRun(ctx)
        totalFreed = Prefs.totalFreed(ctx)
        held = heldRules()
        history = runCatching { Storage.history(ctx).entries().take(30) }.getOrDefault(emptyList())
        if (!hasAccess) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val current = rules
            val guard = protection
            val (t, s, p) = withContext(Dispatchers.IO) {
                Triple(
                    runCatching { Storage.trash().entries() }.getOrDefault(emptyList()),
                    Storage.info(),
                    current.associate { it.id to preview(it, now, guard) },
                )
            }
            trash = t
            storage = s
            previews = p
        }
    }

    private fun preview(rule: RuleConfig, now: Long, guard: Protection): Preview {
        val hits = runCatching { Storage.engine().preview(rule, now, guard) }.getOrDefault(emptyList())
        return Preview(hits.size, hits.sumOf { it.size }, hits.sortedByDescending { it.size }.take(200))
    }

    fun setRule(c: RuleConfig) {
        Prefs.setRule(ctx, c)
        rules = Prefs.rules(ctx)
        if (!hasAccess) return
        val guard = protection
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { preview(c, System.currentTimeMillis(), guard) }
            previews = previews + (c.id to p)
        }
    }

    /** [confirmed]: Regeln, die trotz großer Menge laufen sollen. */
    fun runNow(confirmed: Set<RuleId> = emptySet()) {
        if (busy) return
        busy = true
        askHeld = emptyList()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Runner.run(ctx, confirmed) } }
            busy = false
            val r = result.getOrNull()
            message = when {
                result.isFailure -> "Aufräumen fehlgeschlagen: ${result.exceptionOrNull()?.message ?: "unbekannter Fehler"}"
                r == null -> "Erst den Dateizugriff erlauben"
                r.held.isNotEmpty() && r.moved == 0 -> null
                r.moved == 0 && r.failed == 0 && r.emptyDirs == 0 -> "Nichts zu tun"
                else -> buildString {
                    append("${files(r.moved)} (${size(r.movedBytes)}) in den Papierkorb verschoben")
                    if (r.emptyDirs > 0) append(" · ${r.emptyDirs} leere Ordner entfernt")
                    if (r.failed > 0) append(" · ${files(r.failed)} ließen sich nicht verschieben")
                }
            }
            if (r != null) askHeld = r.held
            refresh()
        }
    }

    fun dismissHeld() {
        askHeld = emptyList()
    }

    fun restore(ids: List<String>, protect: Boolean = false) = trashAction {
        val store = Storage.trash()
        val restored = ids.mapNotNull { store.restore(it) }
        Storage.rescan(ctx, restored.map { it.absolutePath })
        if (protect && restored.isNotEmpty()) {
            saveProtection(protection.copy(files = protection.files + restored.map { it.absolutePath }))
        }
        when {
            restored.size != ids.size -> "${restored.size} von ${ids.size} wiederhergestellt"
            protect -> "Zurückgelegt und geschützt"
            else -> "${files(restored.size)} zurückgelegt"
        }
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

    // ---------- Analyse ----------

    fun analyze() {
        if (analyzing != null || !hasAccess) return
        analyzing = "Speicher wird durchsucht …"
        val guard = protection
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Analysis.scan(Storage.root, Storage.trash().dir) }.getOrNull()
            }
            gone = emptySet()
            report = r
            duplicates = null
            if (r == null) {
                message = "Analyse fehlgeschlagen"
            } else {
                analyzing = "Duplikate werden gesucht …"
                duplicates = withContext(Dispatchers.IO) {
                    runCatching {
                        Duplicates.find(r.files, r.root, guard) { done, total ->
                            analyzing = "Duplikate werden gesucht … $done von $total"
                        }
                    }.getOrDefault(emptyList())
                }
            }
            analyzing = null
        }
    }

    /** Verschiebt einzelne Dateien von Hand in den Papierkorb; [label] steht später als Herkunft dabei. */
    fun trashFiles(paths: List<String>, label: String) = trashAction {
        val store = Storage.trash()
        val now = System.currentTimeMillis()
        var bytes = 0L
        val moved = paths.filter { path ->
            val entry = store.add(File(path), label, now)
            if (entry != null) bytes += entry.size
            entry != null
        }
        Storage.rescan(ctx, moved)
        gone = gone + moved
        if (moved.size == paths.size) "${files(moved.size)} (${size(bytes)}) in den Papierkorb verschoben"
        else "${moved.size} von ${paths.size} verschoben"
    }

    // ---------- Schutzliste ----------

    private fun saveProtection(p: Protection) {
        Prefs.setProtection(ctx, p)
        protection = p
    }

    fun protectFolder(path: String) {
        saveProtection(protection.copy(folders = protection.folders + path))
        refresh()
    }

    fun protectFile(path: String) {
        saveProtection(protection.copy(files = protection.files + path))
        refresh()
    }

    fun unprotect(path: String) {
        saveProtection(Protection(protection.folders - path, protection.files - path))
        refresh()
    }

    // ---------- Einstellungen ----------

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

    fun updateEmptyDirs(v: Boolean) {
        emptyDirs = v
        Prefs.setEmptyDirs(ctx, v)
    }

    fun size(bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    companion object {
        fun files(n: Int): String = if (n == 1) "1 Datei" else "$n Dateien"

        /** Herkunft im Papierkorb für Dateien, die keine Regel verschoben hat. */
        const val MANUAL = "MANUAL"
        const val DUPLICATE = "DUPLICATE"
    }
}
