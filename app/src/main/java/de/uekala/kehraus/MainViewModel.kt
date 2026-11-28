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
import de.uekala.kehraus.core.AppUsage
import de.uekala.kehraus.core.DocCache
import de.uekala.kehraus.core.Documents
import de.uekala.kehraus.core.DownloadSorter
import de.uekala.kehraus.core.Journal
import de.uekala.kehraus.core.Keeper
import de.uekala.kehraus.core.ScreenshotSorter
import de.uekala.kehraus.core.DupGroup
import de.uekala.kehraus.core.Duplicates
import de.uekala.kehraus.core.HistoryEntry
import de.uekala.kehraus.core.Orphan
import de.uekala.kehraus.core.Orphans
import de.uekala.kehraus.core.Hit
import de.uekala.kehraus.core.Protection
import de.uekala.kehraus.core.Report
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.TrashEntry
import de.uekala.kehraus.data.Apps
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
    var auto by mutableStateOf(Prefs.auto(app)); private set
    var notify by mutableStateOf(Prefs.notify(app)); private set
    var emptyDirs by mutableStateOf(Prefs.emptyDirs(app)); private set
    var sortDownloads by mutableStateOf(Prefs.sortDownloads(app)); private set

    /** Wie viele Downloads im Moment einsortiert würden und wie viele sich zurücklegen lassen. */
    var sortPreview by mutableStateOf(0); private set
    var sortPending by mutableStateOf(0); private set

    var sortScreenshots by mutableStateOf(Prefs.sortScreenshots(app)); private set
    var screenshotPreview by mutableStateOf(0); private set
    var screenshotPending by mutableStateOf(0); private set

    var sortDocs by mutableStateOf(Prefs.sortDocs(app)); private set

    /** Texterkennung: geprüfte Fotos, noch offene, gefundene Dokumente am alten Platz, einsortierte zum Zurücknehmen. */
    var docChecked by mutableStateOf(0); private set
    var docOpen by mutableStateOf(0); private set
    var docFound by mutableStateOf(0); private set
    var docPending by mutableStateOf(0); private set
    var lastRun by mutableStateOf<LastRun?>(Prefs.lastRun(app)); private set
    var totalFreed by mutableStateOf(Prefs.totalFreed(app)); private set

    /** Ergebnis der letzten Speicher-Analyse; null, solange noch keine gelaufen ist. */
    var report by mutableStateOf<Report?>(null); private set
    var duplicates by mutableStateOf<List<DupGroup>?>(null); private set

    /** Ordner unter Android/media, deren App nicht mehr installiert ist. */
    var orphans by mutableStateOf<List<Orphan>>(emptyList()); private set

    /** Fortschrittstext, solange die Analyse läuft. */
    var analyzing by mutableStateOf<String?>(null); private set

    /** Pfade, die seit der Analyse in den Papierkorb gewandert sind – die Listen blenden sie aus. */
    var gone by mutableStateOf<Set<String>>(emptySet()); private set

    var usageAccess by mutableStateOf(Apps.hasUsageAccess(app)); private set

    /** Installierte Apps mit Größe und letzter Nutzung; null, solange noch nicht ausgelesen. */
    var apps by mutableStateOf<List<AppUsage>?>(null); private set
    var loadingApps by mutableStateOf(false); private set

    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Was die letzte Aktion in den Papierkorb gelegt hat – für „Rückgängig“ in der Meldung. */
    var undoIds by mutableStateOf<List<String>>(emptyList()); private set

    val trashBytes: Long get() = trash.sumOf { it.size }

    fun refresh() {
        hasAccess = Storage.hasAccess()
        lastRun = Prefs.lastRun(ctx)
        totalFreed = Prefs.totalFreed(ctx)
        history = runCatching { Storage.history(ctx).entries().take(30) }.getOrDefault(emptyList())
        // Nach der Rückkehr aus den Systemeinstellungen (Zugriff erteilt, Cache geleert, App entfernt) neu auslesen.
        usageAccess = Apps.hasUsageAccess(ctx)
        if (usageAccess) loadApps()
        if (!hasAccess) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val current = rules
            val guard = protection
            val (plan, pending) = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadSorter.plan(Storage.root, now, protection = guard).size to DownloadSorter.pending(Storage.sortJournal(ctx))
                }.getOrDefault(0 to 0)
            }
            sortPreview = plan
            sortPending = pending
            withContext(Dispatchers.IO) {
                runCatching {
                    val shots = ScreenshotSorter.plan(Storage.root, now, guard).size
                    val shotsPending = Journal.pending(Storage.screenshotJournal(ctx))
                    val cache = DocCache(Storage.docCache(ctx)).load()
                    listOf(
                        shots, shotsPending, cache.size,
                        Documents.candidates(Storage.root, now, cache, guard).size,
                        Documents.plan(Storage.root, now, cache, guard).size,
                        Journal.pending(Storage.docJournal(ctx)),
                    )
                }.getOrNull()
            }?.let { v ->
                screenshotPreview = v[0]
                screenshotPending = v[1]
                docChecked = v[2]
                docOpen = v[3]
                docFound = v[4]
                docPending = v[5]
            }
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
        // Der Inhaltsvergleich liest viele Dateien ganz. Solange die Regel aus ist, läuft er nur im Reiter Analyse.
        if (rule.id == RuleId.DUPLICATES && !rule.enabled) return Preview(-1, 0, emptyList())
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

    /** Sortiert (wenn eingeschaltet) und zählt die Empfehlungen neu. Verschiebt nichts in den Papierkorb. */
    fun runNow() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            // Von Hand: höchstens eine Minute Texterkennung je Tipp, damit die App nicht ewig „läuft“.
            val result = withContext(Dispatchers.IO) { runCatching { Runner.run(ctx, ocrBudgetMs = 60_000L) } }
            busy = false
            val r = result.getOrNull()
            message = when {
                result.isFailure -> "Fehlgeschlagen: ${result.exceptionOrNull()?.message ?: "unbekannter Fehler"}"
                r == null -> "Erst den Dateizugriff erlauben"
                r.sorted == 0 && r.emptyDirs == 0 -> "Nichts zu sortieren"
                else -> buildString {
                    if (r.sorted > 0) append("${files(r.sorted)} einsortiert")
                    if (r.emptyDirs > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${r.emptyDirs} leere Ordner entfernt")
                    }
                }
            }
            refresh()
        }
    }

    /** Verschiebt, was eine Regel gerade findet, in den Papierkorb. Nur nach Bestätigung in der Oberfläche aufrufen. */
    fun trashRule(id: RuleId) = trashAction(undoable = true) {
        val r = Runner.trash(ctx, setOf(id)) ?: return@trashAction "Erst den Dateizugriff erlauben"
        buildString {
            append("${files(r.moved)} (${size(r.movedBytes)}) in den Papierkorb verschoben")
            if (r.failed > 0) append(" · ${files(r.failed)} ließen sich nicht verschieben")
        }
    }

    /** Alle Empfehlungen der Übersicht auf einmal – mit Rückgängig. Nur nach Bestätigung aufrufen. */
    fun trashRecommended(ids: Set<RuleId>) = trashAction(undoable = true) {
        if (ids.isEmpty()) return@trashAction "Nichts zu verschieben"
        val r = Runner.trash(ctx, ids) ?: return@trashAction "Erst den Dateizugriff erlauben"
        buildString {
            append("${files(r.moved)} (${size(r.movedBytes)}) in den Papierkorb verschoben")
            if (r.failed > 0) append(" · ${files(r.failed)} ließen sich nicht verschieben")
        }
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

    private fun trashAction(undoable: Boolean = false, block: () -> String) {
        if (busy) return
        busy = true
        undoIds = emptyList()
        viewModelScope.launch {
            var added = emptyList<String>()
            val text = withContext(Dispatchers.IO) {
                val before = if (undoable) runCatching { Storage.trash().entries().map { it.id }.toSet() }.getOrDefault(emptySet()) else emptySet()
                val t = runCatching(block).getOrElse { "Fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}" }
                if (undoable) {
                    added = runCatching { Storage.trash().entries().map { it.id }.filter { it !in before } }.getOrDefault(emptyList())
                }
                t
            }
            busy = false
            undoIds = added
            message = text
            refresh()
        }
    }

    /** Legt zurück, was die letzte Aktion verschoben hat. */
    fun undoLast() {
        val ids = undoIds
        if (ids.isEmpty()) return
        undoIds = emptyList()
        gone = gone - ids.mapNotNull { id -> trash.firstOrNull { it.id == id }?.original }.toSet()
        restore(ids)
    }

    // ---------- Apps ----------

    fun loadApps() {
        usageAccess = Apps.hasUsageAccess(ctx)
        if (!usageAccess || loadingApps) return
        loadingApps = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Apps.load(ctx) } }
            loadingApps = false
            if (list.isSuccess) apps = list.getOrNull() else message = "Apps konnten nicht ausgelesen werden"
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
            orphans = if (r == null) emptyList() else withContext(Dispatchers.IO) {
                runCatching { Orphans.find(r, Apps.installed(ctx)) }.getOrDefault(emptyList())
            }
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
    fun trashFiles(paths: List<String>, label: String) = trashAction(undoable = true) {
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

    /** Legt den ganzen Ordner einer nicht mehr installierten App in den Papierkorb. */
    fun trashOrphan(orphan: Orphan) {
        val r = report ?: return
        trashFiles(Orphans.filesOf(r, orphan).map { it.path }, ORPHAN)
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

    fun updateAuto(v: Boolean) {
        auto = v
        Prefs.setAuto(ctx, v)
        CleanWorker.sync(ctx)
    }

    fun updateNotify(v: Boolean) {
        notify = v
        Prefs.setNotify(ctx, v)
    }

    fun updateSortDownloads(v: Boolean) {
        sortDownloads = v
        Prefs.setSortDownloads(ctx, v)
    }

    /** Legt alle einsortierten Downloads zurück an ihren alten Platz. */
    fun undoSort() = trashAction {
        val undone = DownloadSorter.undo(Storage.sortJournal(ctx))
        Storage.rescan(ctx, undone.flatMap { listOf(it.from, it.to) })
        "${files(undone.size)} zurück in den Download-Ordner gelegt"
    }

    fun updateSortScreenshots(v: Boolean) {
        sortScreenshots = v
        Prefs.setSortScreenshots(ctx, v)
    }

    fun undoScreenshots() = trashAction {
        val undone = Journal.undo(Storage.screenshotJournal(ctx))
        Storage.rescan(ctx, undone.flatMap { listOf(it.from, it.to) })
        "${files(undone.size)} zurück nach Screenshots gelegt"
    }

    fun updateSortDocs(v: Boolean) {
        sortDocs = v
        Prefs.setSortDocs(ctx, v)
    }

    fun undoDocs() = trashAction {
        val undone = Journal.undo(Storage.docJournal(ctx))
        Storage.rescan(ctx, undone.flatMap { listOf(it.from, it.to) })
        "${files(undone.size)} an ihren alten Platz zurückgelegt"
    }

    /** Legt ein WhatsApp-Bild ins Album „WhatsApp behalten“, damit keine Regel es mehr empfiehlt. */
    fun keepFile(path: String) = trashAction {
        val kept = Keeper.keep(Storage.root, File(path)) ?: return@trashAction "Ließ sich nicht verschieben"
        Storage.rescan(ctx, listOf(path, kept.absolutePath))
        gone = gone + path
        "Nach „WhatsApp behalten“ gelegt"
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
        const val DUPLICATE = "DUPLICATES"
        const val ORPHAN = "ORPHAN"
    }
}
