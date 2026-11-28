package de.uekala.kehraus.ui

import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uekala.kehraus.MainViewModel
import de.uekala.kehraus.MainViewModel.Companion.files
import de.uekala.kehraus.core.AppAdvice
import de.uekala.kehraus.core.AppUsage
import de.uekala.kehraus.core.DupGroup
import de.uekala.kehraus.core.FileInfo
import de.uekala.kehraus.core.Group
import de.uekala.kehraus.core.HistoryEntry
import de.uekala.kehraus.core.Kind
import de.uekala.kehraus.core.Report
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.Rules
import de.uekala.kehraus.core.TrashEntry
import de.uekala.kehraus.data.Apps
import de.uekala.kehraus.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AGE_OPTIONS = listOf(7, 14, 30, 60, 90, 180, 365)
private val SIZE_OPTIONS = listOf(50, 100, 200, 500, 1000)

private fun days(n: Int): String = when (n) {
    1 -> "1 Tag"
    365 -> "1 Jahr"
    else -> "$n Tage"
}

private fun megabytes(n: Int): String = if (n >= 1000) "${n / 1000} GB" else "$n MB"

/** Titel einer Regel mit ihrer Gruppe davor, wo der Titel allein nicht eindeutig wäre. */
private val RuleId.fullTitle: String get() = if (group == Group.WHATSAPP) "WhatsApp · $title" else title

private fun dateTime(ms: Long): String = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(ms))

@Composable
fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            val undo = vm.undoIds.isNotEmpty()
            val result = snackbar.showSnackbar(
                message,
                actionLabel = if (undo) "Rückgängig" else null,
                duration = if (undo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            vm.message = null
            if (result == SnackbarResult.ActionPerformed) vm.undoLast()
        }
    }
    // Zurück-Taste: erst zur Übersicht, dann wie gewohnt aus der App.
    BackHandler(enabled = tab != 0) { tab = 0 }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                val tabs = listOf(
                    "Übersicht" to Icons.Filled.Home,
                    "Regeln" to Icons.AutoMirrored.Filled.List,
                    "Analyse" to Icons.Filled.Search,
                    "Apps" to AppsIcon,
                    "Papierkorb" to Icons.Filled.Delete,
                )
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (i == 4 && vm.trash.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(if (vm.trash.size > 99) "99+" else "${vm.trash.size}") } }) {
                                    Icon(icon, contentDescription = null)
                                }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = { Text(label, maxLines = 1, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> Overview(vm, onOpenRules = { tab = 1 }, onOpenAnalysis = { tab = 2 }, onOpenApps = { tab = 3 })
                1 -> RulesScreen(vm)
                2 -> AnalysisScreen(vm)
                3 -> AppsScreen(vm)
                else -> TrashScreen(vm)
            }
            if (vm.busy || vm.analyzing != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                    color = Accent,
                    trackColor = Bg,
                )
            }
        }
    }
}

// ---------- Übersicht ----------

@Composable
private fun Overview(vm: MainViewModel, onOpenRules: () -> Unit, onOpenAnalysis: () -> Unit, onOpenApps: () -> Unit) {
    val ctx = LocalContext.current
    var confirmAll by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Kehraus")

        if (!vm.hasAccess) {
            Panel("Zugriff nötig") {
                Dim(
                    "Kehraus braucht den Zugriff auf alle Dateien, um Aufgeräumtes ohne Rückfrage in den " +
                        "Papierkorb zu verschieben. Die App hat keinen Internetzugang – nichts verlässt das Gerät."
                )
                Button(onClick = { Storage.openAccessSettings(ctx) }) { Text("Zugriff erlauben") }
            }
        }

        vm.storage?.let { s ->
            Panel("Speicher") {
                val used = if (s.total > 0) 1f - s.free.toFloat() / s.total.toFloat() else 0f
                Text("${vm.size(s.free)} frei von ${vm.size(s.total)}", color = TextMain)
                LinearProgressIndicator(
                    progress = { used.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (used > 0.9f) Warn else Accent,
                    trackColor = Track,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                if (s.low) {
                    Text(
                        if (vm.trashBytes > 0) "Der Speicher wird knapp. Im Papierkorb liegen ${vm.size(vm.trashBytes)} – leeren schafft sofort Platz."
                        else "Der Speicher wird knapp.",
                        color = Warn, fontSize = 13.sp,
                    )
                }
            }
        }

        vm.apps?.let { apps ->
            val cache = apps.sumOf { it.cacheBytes }
            if (cache >= 1024L * 1024 * 1024) {
                Panel("App-Cache") {
                    KeyValue("Im Cache der Apps", vm.size(cache))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dim("Leeren kann den nur Android selbst, App für App.", Modifier.weight(1f), size = 12)
                        TextButton(onClick = onOpenApps) { Text("Apps") }
                    }
                }
            }
        }

        val active = vm.rules.filter { it.enabled }
        val recs = active.mapNotNull { r -> vm.previews[r.id]?.takeIf { it.count > 0 }?.let { r.id to it } }
        // Duplikate aus der Analyse, solange die Duplikat-Regel aus ist – sonst stehen sie schon in den Empfehlungen.
        val dupRuleOn = active.any { it.id == RuleId.DUPLICATES }
        val analysisDups = if (dupRuleOn) emptyList() else vm.duplicates.orEmpty()
        Panel("Empfehlungen") {
            when {
                active.isEmpty() && analysisDups.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Dim("Noch ist keine Regel eingeschaltet.", Modifier.weight(1f))
                    TextButton(onClick = onOpenRules) { Text("Regeln") }
                }
                recs.isEmpty() && analysisDups.isEmpty() -> Dim("Im Moment gibt es nichts zu empfehlen.")
                else -> {
                    if (recs.isNotEmpty()) {
                        Text("${files(recs.sumOf { it.second.count })} · ${vm.size(recs.sumOf { it.second.bytes })} könnten weg", color = Accent)
                        recs.forEach { (id, p) -> KeyValue(id.fullTitle, "${files(p.count)} · ${vm.size(p.bytes)}") }
                        Button(
                            onClick = { confirmAll = true },
                            enabled = vm.hasAccess && !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Alles Empfohlene in den Papierkorb") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dim("Einzeln ansehen und auswählen geht unter „Regeln“.", Modifier.weight(1f), size = 12)
                            TextButton(onClick = onOpenRules) { Text("Regeln") }
                        }
                    }
                    if (analysisDups.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Doppelte Dateien", color = TextMain, fontSize = 14.sp)
                                Dim("${analysisDups.size} Gruppen · ${vm.size(analysisDups.sumOf { it.wasted })} doppelt belegt", size = 12)
                            }
                            TextButton(onClick = onOpenAnalysis) { Text("Analyse") }
                        }
                    }
                }
            }
        }
        if (confirmAll) {
            AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("Alles Empfohlene verschieben?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recs.forEach { (id, p) -> KeyValue(id.fullTitle, "${files(p.count)} · ${vm.size(p.bytes)}") }
                        Dim("Alles landet im Papierkorb und lässt sich dort zurücklegen. Endgültig gelöscht wird erst nach der Frist oder beim Leeren.", size = 13)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { confirmAll = false; vm.trashRecommended(recs.map { it.first }.toSet()) }) { Text("Verschieben") }
                },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Abbrechen") } },
            )
        }

        Panel("Sortieren") {
            val last = vm.lastRun
            if (last == null) {
                Dim("Noch kein Lauf.")
            } else {
                KeyValue("Letzter Lauf", dateTime(last.at))
                KeyValue("Einsortiert", if (last.sorted == 0) "nichts" else files(last.sorted))
                if (last.emptyDirs > 0) KeyValue("Leere Ordner entfernt", "${last.emptyDirs}")
            }
            Button(
                onClick = { vm.runNow() },
                enabled = vm.hasAccess && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.busy) "Läuft …" else "Jetzt sortieren und prüfen") }
            if (!vm.sortDownloads && !vm.emptyDirs) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dim("Sortieren ist aus. Einschalten unter „Regeln“ bei „Weitere Aufgaben“.", Modifier.weight(1f), size = 12)
                    TextButton(onClick = onOpenRules) { Text("Regeln") }
                }
            }
        }

        Panel("Papierkorb") {
            KeyValue("Im Papierkorb", "${files(vm.trash.size)} · ${vm.size(vm.trashBytes)}")
            KeyValue("Bisher endgültig gelöscht", vm.size(vm.totalFreed))
            Dim("Der Papierkorb löscht nichts von selbst. Platz wird erst frei, wenn du ihn leerst.", size = 12)
        }
    }
}

private fun historyTitle(e: HistoryEntry): String = when (e.what) {
    HistoryEntry.PURGED -> "Papierkorb: Frist abgelaufen"
    HistoryEntry.EMPTY_DIRS -> "Leere Ordner"
    HistoryEntry.SORTED -> "Downloads sortiert"
    else -> ruleTitle(e.what)
}

private fun historyDetail(vm: MainViewModel, e: HistoryEntry): String = when {
    e.what == HistoryEntry.PURGED -> "${files(e.count)} endgültig gelöscht · ${vm.size(e.bytes)}"
    e.what == HistoryEntry.EMPTY_DIRS -> "${e.count} entfernt"
    e.what == HistoryEntry.SORTED -> "${files(e.count)} in Unterordner gelegt"
    e.held -> "angehalten bei ${files(e.count)} · ${vm.size(e.bytes)}"
    else -> buildString {
        append("${files(e.count)} verschoben · ${vm.size(e.bytes)}")
        if (e.failed > 0) append(" · ${e.failed} fehlgeschlagen")
    }
}

// ---------- Regeln ----------

@Composable
private fun RulesScreen(vm: MainViewModel) {
    var showHits by remember { mutableStateOf<RuleId?>(null) }
    var confirmTrash by remember { mutableStateOf<RuleId?>(null) }
    var pickFolder by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Regeln")
        Dim("Regeln suchen, was weg könnte. Sie verschieben nichts von selbst: Eingeschaltete Regeln erscheinen als Empfehlung auf der Übersicht. In den Papierkorb kommt nur, was du dort oder hier per Knopf bestätigst.")
        Panel("Automatik") {
            SwitchRow(
                "Täglich sortieren und prüfen",
                "Einmal am Tag im Hintergrund, nicht bei schwachem Akku. Verschiebt nichts in den Papierkorb.",
                vm.auto,
            ) { vm.updateAuto(it) }
            SwitchRow("Melden, wenn es etwas gibt", "Was einsortiert wurde, Empfehlungen und ein voller Papierkorb – höchstens einmal pro Woche.", vm.notify) { vm.updateNotify(it) }
        }

        Group.entries.forEach { group ->
            SectionTitle(group.title)
            vm.rules.filter { it.id.group == group }.forEach { rule -> RuleCard(vm, rule, onShow = { showHits = rule.id }, onTrash = { confirmTrash = rule.id }) }
        }

        SectionTitle("Sortieren")
        Dim("Sortieren verschiebt nur in Ordner und löscht nichts. Es läuft täglich und über „Jetzt sortieren und prüfen“; jede Sortierung lässt sich zurücknehmen.", size = 13)
        Panel {
            SwitchRow(
                "Downloads nach Art",
                "Lose Dateien im Download-Ordner, älter als 7 Tage, wandern in Unterordner: Dokumente, Bilder, Videos, Audio, Archive, Installationsdateien.",
                vm.sortDownloads,
            ) { vm.updateSortDownloads(it) }
            SortStatus(
                if (vm.sortPreview == 0) "Im Moment gibt es nichts einzusortieren." else "Würde jetzt ${files(vm.sortPreview)} einsortieren.",
                vm.sortPending, !vm.busy,
            ) { vm.undoSort() }
        }
        Panel {
            SwitchRow(
                "Screenshots nach Monat",
                "Screenshots aus früheren Monaten wandern in Unterordner wie „2026-10“. Der laufende Monat bleibt, wo er ist. Die Galerie zeigt jeden Monat als eigenes Album.",
                vm.sortScreenshots,
            ) { vm.updateSortScreenshots(it) }
            SortStatus(
                if (vm.screenshotPreview == 0) "Im Moment gibt es nichts einzusortieren." else "Würde jetzt ${files(vm.screenshotPreview)} einsortieren.",
                vm.screenshotPending, !vm.busy,
            ) { vm.undoScreenshots() }
        }
        Panel {
            SwitchRow(
                "Belege und Dokumente",
                "Die Texterkennung prüft Fotos aus Kamera und WhatsApp direkt auf dem Handy. Rechnungen, Kassenbons und Briefe wandern nach „Pictures/Dokumente“.",
                vm.sortDocs,
            ) { vm.updateSortDocs(it) }
            Dim(
                "Achtung: Sichert Google Fotos nur den Kameraordner, sind einsortierte Belege nicht mehr in der Sicherung. WhatsApp-Bilder lassen sich danach im Chat nicht mehr öffnen.",
                size = 12,
            )
            if (vm.sortDocs) {
                Dim(
                    when {
                        vm.docChecked == 0 && vm.docOpen == 0 -> "Keine Fotos zum Prüfen."
                        vm.docOpen > 0 -> "${vm.docChecked} Fotos geprüft, ${vm.docOpen} noch offen. Pro Lauf schafft die Erkennung einige Hundert."
                        else -> "Alle ${vm.docChecked} Fotos geprüft."
                    },
                    size = 13,
                )
            }
            SortStatus(
                if (vm.docFound == 0) "Kein erkanntes Dokument wartet aufs Einsortieren." else "${files(vm.docFound)} als Dokument erkannt, noch nicht einsortiert.",
                vm.docPending, !vm.busy,
            ) { vm.undoDocs() }
        }
        Panel {
            SwitchRow(
                "Leere Ordner entfernen",
                "In Download, Pictures, DCIM, Movies, Documents und Music. Leere Ordner werden direkt gelöscht, nicht in den Papierkorb gelegt.",
                vm.emptyDirs,
            ) { vm.updateEmptyDirs(it) }
        }

        SectionTitle("Schutzliste")
        Panel {
            Dim("Was hier steht, fasst keine Regel an – bei Ordnern gilt das für alles darunter.", size = 13)
            val guarded = vm.protection.folders.sorted().map { it to true } + vm.protection.files.sorted().map { it to false }
            if (guarded.isEmpty()) Dim("Noch nichts geschützt.")
            guarded.forEach { (path, isFolder) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Storage.relative(path), color = TextMain, fontSize = 14.sp)
                        Dim(if (isFolder) "Ordner" else "Datei", size = 12)
                    }
                    TextButton(onClick = { vm.unprotect(path) }) { Text("Entfernen") }
                }
            }
            OutlinedButton(onClick = { pickFolder = true }, enabled = vm.hasAccess) { Text("Ordner schützen") }
        }
    }
    showHits?.let { id -> HitsDialog(vm, id) { showHits = null } }
    confirmTrash?.let { id ->
        val p = vm.previews[id]
        AlertDialog(
            onDismissRequest = { confirmTrash = null },
            title = { Text("In den Papierkorb verschieben?") },
            text = {
                Text(
                    "${files(p?.count ?: 0)} (${vm.size(p?.bytes ?: 0)}) aus „${id.fullTitle}“ wandern in den Papierkorb. " +
                        "Dort bleiben sie, bis du ihn leerst – zurücklegen geht jederzeit."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.trashRule(id); confirmTrash = null }) { Text("Verschieben") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = null }) { Text("Abbrechen") } },
        )
    }
    if (pickFolder) {
        FolderPicker(
            onPick = { vm.protectFolder(it.absolutePath); pickFolder = false },
            onClose = { pickFolder = false },
        )
    }
}

/** Kleiner Ordner-Browser: durch den Gerätespeicher blättern und einen Ordner auswählen. */
@Composable
private fun FolderPicker(onPick: (File) -> Unit, onClose: () -> Unit) {
    val root = remember { Storage.root }
    var dir by remember { mutableStateOf(root) }
    val children by produceState(emptyList<File>(), dir) {
        value = withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ordner schützen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dim(Storage.relative(dir.absolutePath), size = 13)
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (dir != root) {
                        item { PickerRow("‹ zurück", TextDim) { dir = dir.parentFile ?: root } }
                    }
                    items(children) { child -> PickerRow(child.name, TextMain) { dir = child } }
                    if (children.isEmpty()) item { Dim("Keine Unterordner.", Modifier.padding(vertical = 10.dp), size = 13) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(dir) }, enabled = dir != root) { Text("Diesen Ordner schützen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@Composable
private fun PickerRow(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
private fun RuleCard(vm: MainViewModel, rule: RuleConfig, onShow: () -> Unit, onTrash: () -> Unit) {
    Panel(
        title = rule.id.title,
        trailing = { Switch(checked = rule.enabled, onCheckedChange = { vm.setRule(rule.copy(enabled = it)) }) },
    ) {
        Dim(rule.id.description, size = 13)
        if (rule.id.usesAge) {
            Stepper("Älter als", rule.ageDays, AGE_OPTIONS, ::days) { vm.setRule(rule.copy(ageDays = it)) }
        }
        if (rule.id.defaultMinSizeMb > 0) {
            Stepper("Ab Größe", rule.minSizeMb, SIZE_OPTIONS, ::megabytes) { vm.setRule(rule.copy(minSizeMb = it)) }
        }
        val p = vm.previews[rule.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !vm.hasAccess -> "Vorschau erst mit Dateizugriff"
                    p == null -> "Wird gezählt …"
                    p.count < 0 -> "Vorschau im Reiter „Analyse“"
                    p.count == 0 -> "Trifft im Moment nichts"
                    else -> "Trifft jetzt ${files(p.count)} · ${vm.size(p.bytes)}"
                },
                color = if (p != null && p.count > 0) Accent else TextDim,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (p != null && p.count > 0) TextButton(onClick = onShow) { Text("Ansehen") }
        }
        if (p != null && p.count > 0) {
            OutlinedButton(onClick = onTrash, enabled = !vm.busy) { Text("In den Papierkorb …") }
        }
    }
}

@Composable
private fun HitsDialog(vm: MainViewModel, id: RuleId, onClose: () -> Unit) {
    val p = vm.previews[id]
    val now = remember { System.currentTimeMillis() }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        title = { Text(id.fullTitle) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val keep = id == RuleId.WHATSAPP
                item {
                    Dim(
                        if (keep) "„Behalten“ legt ein Bild ins Album „WhatsApp behalten“ – dort empfiehlt es keine Regel mehr."
                        else "„Schützen“ nimmt eine Datei dauerhaft von allen Regeln aus.",
                        size = 12,
                    )
                }
                items(p?.hits.orEmpty().filter { it.file.path !in vm.gone }, key = { it.file.path }) { hit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val kind = Rules.kindOf(hit.file.name)
                        if (kind == Kind.IMAGE || kind == Kind.VIDEO) {
                            Thumb(hit.file, hit.file.name, 44)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(hit.file.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val age = ((now - hit.modified) / Rules.DAY_MS).toInt()
                            Dim("${vm.size(hit.size)} · ${days(age)} alt · ${hit.file.parentFile?.name.orEmpty()}", size = 12)
                        }
                        if (keep) {
                            TextButton(onClick = { vm.keepFile(hit.file.absolutePath) }, enabled = !vm.busy) { Text("Behalten") }
                        } else {
                            TextButton(onClick = { vm.protectFile(hit.file.absolutePath) }) { Text("Schützen") }
                        }
                    }
                }
                if (p != null && p.count > p.hits.size) {
                    item { Dim("… und ${p.count - p.hits.size} weitere", size = 12) }
                }
            }
        },
    )
}

// ---------- Analyse ----------

@Composable
private fun AnalysisScreen(vm: MainViewModel) {
    var selected by remember { mutableStateOf<FileInfo?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val report = vm.report
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Analyse")
        Dim("Zeigt, wo der Platz hingeht. Hier passiert nichts von selbst – du entscheidest Datei für Datei oder Gruppe für Gruppe.")
        Button(
            onClick = { vm.analyze() },
            enabled = vm.hasAccess && vm.analyzing == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(vm.analyzing ?: if (report == null) "Speicher analysieren" else "Neu analysieren") }
        if (!vm.hasAccess) Dim("Erst auf der Übersicht den Dateizugriff erlauben.")

        if (report != null) {
            Panel("Nach Art") {
                val total = report.total.bytes.coerceAtLeast(1)
                report.byCategory.entries.sortedByDescending { it.value.bytes }.forEach { (cat, use) ->
                    KeyValue(cat.title, "${vm.size(use.bytes)} · ${files(use.files)}")
                    LinearProgressIndicator(
                        progress = { (use.bytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent,
                        trackColor = Track,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
                Dim(
                    "Zusammen ${vm.size(report.total.bytes)} in ${files(report.total.files)}. Apps und ihre Daten zählen nicht mit – " +
                        "darauf hat Kehraus keinen Zugriff.",
                    size = 12,
                )
            }

            FolderPanel(vm, report) { selected = it }

            Panel("Größte Dateien") {
                report.largest.filter { it.path !in vm.gone }.take(30).forEach { f -> FileRow(vm, f) { selected = f } }
            }

            DuplicatesPanel(vm) { confirmAll = true }

            val orphans = vm.orphans.filter { o -> vm.gone.none { it.startsWith(o.path + "/") } }
            if (orphans.isNotEmpty()) {
                Panel("Reste entfernter Apps") {
                    Dim("Ordner von Apps, die nicht mehr installiert sind. Bitte kurz prüfen – Apps aus einem zweiten Profil (Arbeitsprofil, App-Klon) erkennt Kehraus nicht als installiert.", size = 12)
                    orphans.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.pkg, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Dim("${files(o.usage.files)} · ${vm.size(o.usage.bytes)}", size = 12)
                            }
                            TextButton(onClick = { vm.trashOrphan(o) }, enabled = !vm.busy) { Text("Weg") }
                        }
                    }
                }
            }
        }
    }

    selected?.let { f ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Thumb(File(f.path), f.name, 200)
                    Dim(Storage.relative(f.path), size = 12)
                    Dim("${vm.size(f.size)} · geändert am ${dateTime(f.modified)}", size = 12)
                    TextButton(onClick = { vm.protectFile(f.path); selected = null }) { Text("Schützen") }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.trashFiles(listOf(f.path), MainViewModel.MANUAL); selected = null }) {
                    Text("In den Papierkorb")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Schließen") } },
        )
    }

    if (confirmAll) {
        val copies = vm.duplicates.orEmpty().flatMap { it.copies }.filter { it.path !in vm.gone }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Alle Kopien verschieben?") },
            text = {
                Text(
                    "${files(copies.size)} (${vm.size(copies.sumOf { it.size })}) wandern in den Papierkorb. " +
                        "Von jeder Gruppe bleibt eine Datei an ihrem Platz."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.trashFiles(copies.map { it.path }, MainViewModel.DUPLICATE); confirmAll = false }) {
                    Text("Verschieben")
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun FolderPanel(vm: MainViewModel, report: Report, onFile: (FileInfo) -> Unit) {
    var dir by remember(report) { mutableStateOf(report.root) }
    // Zurück-Taste geht erst einen Ordner hoch, bevor sie den Reiter verlässt.
    BackHandler(enabled = dir != report.root) { dir = dir.substringBeforeLast('/', report.root) }
    Panel("Ordner") {
        Dim(Storage.relative(dir) + " · " + vm.size(report.folders[dir]?.bytes ?: 0), size = 13)
        if (dir != report.root) {
            Text(
                "‹ zurück",
                color = Accent,
                modifier = Modifier.fillMaxWidth().clickable { dir = dir.substringBeforeLast('/', report.root) }.padding(vertical = 8.dp),
            )
        }
        report.subfolders(dir).take(25).forEach { (path, use) ->
            Row(
                Modifier.fillMaxWidth().clickable { dir = path }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(path.substringAfterLast('/') + " ›", color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(vm.size(use.bytes), color = TextDim, fontSize = 14.sp)
            }
        }
        val here = remember(report, dir, vm.gone) { report.filesIn(dir, 15).filter { it.path !in vm.gone } }
        here.forEach { f -> FileRow(vm, f) { onFile(f) } }
    }
}

@Composable
private fun FileRow(vm: MainViewModel, f: FileInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dim(Storage.relative(f.parent), size = 12)
        }
        Spacer(Modifier.width(8.dp))
        Text(vm.size(f.size), color = TextDim, fontSize = 14.sp)
    }
}

@Composable
private fun DuplicatesPanel(vm: MainViewModel, onAll: () -> Unit) {
    val all = vm.duplicates
    val groups = all.orEmpty().map { g -> g.copy(copies = g.copies.filter { it.path !in vm.gone }) }
        .filter { it.copies.isNotEmpty() && it.keep.path !in vm.gone }
    Panel("Exakte Duplikate") {
        when {
            all == null -> Dim("Wird gesucht …")
            groups.isEmpty() -> Dim("Keine Dateien mit gleichem Inhalt gefunden.")
            else -> {
                Text("${groups.size} Gruppen · ${vm.size(groups.sumOf { it.wasted })} einsparbar", color = Accent)
                Dim("Inhalt Byte für Byte gleich. Es bleibt jeweils die Datei am besten Platz, bevorzugt im Kameraordner.", size = 12)
                OutlinedButton(onClick = onAll, enabled = !vm.busy) { Text("Alle Kopien in den Papierkorb") }
                groups.take(40).forEach { g -> DupRow(vm, g) }
                if (groups.size > 40) Dim("… und ${groups.size - 40} weitere Gruppen", size = 12)
            }
        }
    }
}

@Composable
private fun DupRow(vm: MainViewModel, g: DupGroup) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Thumb(File(g.keep.path), g.keep.name, 48)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(g.keep.name, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dim("bleibt: ${Storage.relative(g.keep.parent)}", size = 12)
            Dim("${files(g.copies.size)} doppelt · ${vm.size(g.wasted)} · in ${g.copies.map { it.parent.substringAfterLast('/') }.distinct().joinToString(", ")}", size = 12)
        }
        TextButton(onClick = { vm.trashFiles(g.copies.map { it.path }, MainViewModel.DUPLICATE) }, enabled = !vm.busy) { Text("Weg") }
    }
}

// ---------- Apps ----------

@Composable
private fun AppsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val apps = vm.apps
    LaunchedEffect(vm.usageAccess) {
        if (vm.usageAccess && vm.apps == null) vm.loadApps()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Apps")
        Dim("Der meiste Platz steckt oft in Apps und ihren Daten. Kehraus kann fremde Apps weder leeren noch entfernen – ein Tipp öffnet die Systemseite der App, dort gibt es „Cache leeren“ und „Deinstallieren“.")

        if (!vm.usageAccess) {
            Panel("Zugriff nötig") {
                Dim("Für Größe und letzte Nutzung der Apps braucht Kehraus den Nutzungsdatenzugriff. In der Liste Kehraus auswählen und den Zugriff erlauben.")
                Dim("Ist der Schalter grau („eingeschränkte Einstellung“): in den Android-Einstellungen unter Apps → Kehraus oben rechts auf die drei Punkte tippen und „Eingeschränkte Einstellungen zulassen“ wählen. Danach lässt er sich umlegen.", size = 12)
                Button(onClick = { Apps.openUsageSettings(ctx) }) { Text("Zugriff erlauben") }
            }
        } else if (apps == null) {
            Dim(if (vm.loadingApps) "Apps werden ausgelesen …" else "Noch nicht ausgelesen.")
        } else {
            val now = remember(apps) { System.currentTimeMillis() }
            val unused = remember(apps) { AppAdvice.unused(apps, now, self = ctx.packageName) }
            val neverSeen = remember(apps) { AppAdvice.neverSeen(apps, self = ctx.packageName) }
            val cache = remember(apps) { AppAdvice.byCache(apps) }
            val content = remember(apps) { AppAdvice.byContent(apps) }
            val sorted = remember(apps, showSystem) { AppAdvice.bySize(apps, showSystem) }

            Panel("Überblick") {
                KeyValue("Apps mit Daten", "${apps.size} · ${vm.size(apps.sumOf { it.total })}")
                KeyValue("davon Cache", vm.size(apps.sumOf { it.cacheBytes }))
                KeyValue("davon selbst installiert", "${apps.count { !it.system }} · ${vm.size(apps.filter { !it.system }.sumOf { it.total })}")
                vm.storage?.let { s -> KeyValue("Belegt insgesamt", vm.size(s.total - s.free)) }
                OutlinedButton(onClick = { vm.loadApps() }, enabled = !vm.loadingApps) {
                    Text(if (vm.loadingApps) "Liest aus …" else "Neu auslesen")
                }
            }

            Panel("Größter Cache") {
                if (cache.isEmpty()) {
                    Dim("Kein nennenswerter Cache.")
                } else {
                    Text("${cache.size} Apps · ${vm.size(cache.sumOf { it.cacheBytes })}", color = Accent)
                    Dim("Cache leeren ist gefahrlos: Die App lädt bei Bedarf neu. Der Platz kann sich aber wieder füllen.", size = 12)
                    cache.take(25).forEach { AppRow(vm, it, now, vm.size(it.cacheBytes)) }
                }
            }

            if (content.isNotEmpty()) {
                Panel("Viele eigene Daten") {
                    Text("${content.size} Apps · ${vm.size(content.sumOf { it.contentBytes })}", color = Accent)
                    Dim("Daten ohne den Cache: Downloads, Mediatheken, Chats. „Cache leeren“ hilft hier nicht – aufräumen geht nur in der App selbst, etwa heruntergeladene Musik oder Karten entfernen.", size = 12)
                    content.take(15).forEach { AppRow(vm, it, now, vm.size(it.contentBytes)) }
                }
            }

            Panel("Lange nicht genutzt") {
                if (unused.isEmpty()) {
                    Dim("Keine größere App, die seit 60 Tagen ungenutzt ist.")
                } else {
                    Text("${unused.size} Apps · ${vm.size(unused.sumOf { it.total })}", color = Accent)
                    Dim("Selbst installiert, mindestens 50 MB, seit 60 Tagen nicht geöffnet.", size = 12)
                    unused.take(25).forEach { AppRow(vm, it, now, vm.size(it.total)) }
                }
            }

            if (neverSeen.isNotEmpty()) {
                Panel("Keine Nutzung aufgezeichnet") {
                    Text("${neverSeen.size} Apps · ${vm.size(neverSeen.sumOf { it.total })}", color = TextMain)
                    Dim("Für diese Apps kennt das System keinen Zeitpunkt. Das kann „nie geöffnet“ heißen – oder ein Dienst sein, der nur im Hintergrund läuft. Bitte einzeln prüfen.", size = 12)
                    neverSeen.take(25).forEach { AppRow(vm, it, now, vm.size(it.total)) }
                }
            }

            Panel("Alle nach Größe") {
                SwitchRow("System-Apps zeigen", null, showSystem) { showSystem = it }
                sorted.take(50).forEach { AppRow(vm, it, now, vm.size(it.total)) }
                if (sorted.size > 50) Dim("… und ${sorted.size - 50} weitere", size = 12)
            }
        }
    }
}

@Composable
private fun AppRow(vm: MainViewModel, app: AppUsage, now: Long, value: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { Apps.openDetails(ctx, app.pkg) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label + " ›", color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val idle = app.idleDays(now)
            val used = when {
                app.service -> "läuft als Dienst"
                idle == null -> "keine Nutzung aufgezeichnet"
                idle == 0 -> "heute genutzt"
                idle == 1 -> "zuletzt vor 1 Tag"
                else -> "zuletzt vor $idle Tagen"
            }
            Dim("App ${vm.size(app.appBytes)} · Daten ${vm.size(app.dataBytes)} · Cache ${vm.size(app.cacheBytes)} · $used", size = 12)
        }
        Spacer(Modifier.width(8.dp))
        Text(value, color = TextDim, fontSize = 14.sp)
    }
}

// ---------- Papierkorb ----------

@Composable
private fun TrashScreen(vm: MainViewModel) {
    var selected by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashEntry?>(null) }
    val store = remember { Storage.trash() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Heading("Papierkorb") }
        item {
            Panel {
                Text(
                    if (vm.trash.isEmpty()) "Der Papierkorb ist leer."
                    else "${files(vm.trash.size)} · ${vm.size(vm.trashBytes)}",
                    color = TextMain,
                )
                Dim(
                    if (vm.trash.isEmpty()) "Was Regeln, Analyse oder du verschieben, landet hier und lässt sich zurücklegen."
                    else "Der Papierkorb löscht nichts von selbst. Erst „Leeren“ gibt den Platz frei. Tippen öffnet eine Datei.",
                    size = 13,
                )
                if (vm.trash.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.restore(vm.trash.map { it.id }) },
                            enabled = !vm.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Alle zurücklegen") }
                        OutlinedButton(
                            onClick = { confirmEmpty = true },
                            enabled = !vm.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Leeren", color = Bad) }
                    }
                }
            }
        }
        items(vm.trash, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface)
                    .clickable { selected = e }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(store.fileOf(e), e.name, 52)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Dim("${folderOf(e)} · ${vm.size(e.size)} · seit ${dateTime(e.trashedAt).substringBefore(',')}", size = 12)
                }
            }
        }
        if (vm.history.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Panel("Verlauf") {
                    vm.history.take(12).forEach { e ->
                        Column {
                            Text(historyTitle(e), color = TextMain, fontSize = 14.sp)
                            Dim("${dateTime(e.at)} · ${historyDetail(vm, e)}", size = 12)
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Thumb(store.fileOf(e), e.name, 220)
                    Dim(e.original, size = 12)
                    Dim("Verschoben am ${dateTime(e.trashedAt)} · ${ruleTitle(e.rule)} · ${vm.size(e.size)}", size = 12)
                    TextButton(onClick = { vm.restore(listOf(e.id), protect = true); selected = null }) {
                        Text("Zurücklegen und künftig schützen")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.restore(listOf(e.id)); selected = null }) { Text("Zurücklegen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = e; selected = null }) { Text("Endgültig löschen", color = Bad) }
            },
        )
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Endgültig löschen?") },
            text = { Text("„${e.name}“ (${vm.size(e.size)}) wird gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(listOf(e.id)); confirmDelete = null }) { Text("Löschen", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Papierkorb leeren?") },
            text = {
                Text("${files(vm.trash.size)} (${vm.size(vm.trashBytes)}) werden endgültig gelöscht. Das lässt sich nicht rückgängig machen.")
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(vm.trash.map { it.id }); confirmEmpty = false }) { Text("Leeren", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Abbrechen") } },
        )
    }
}

private fun folderOf(e: TrashEntry): String = File(e.original).parentFile?.name ?: "–"

private fun ruleTitle(name: String): String = when (name) {
    MainViewModel.MANUAL -> "von Hand"
    MainViewModel.DUPLICATE -> "Duplikat"
    MainViewModel.ORPHAN -> "Rest einer entfernten App"
    else -> RuleId.entries.firstOrNull { it.name == name }?.fullTitle ?: name
}

// ---------- Bausteine ----------

@Composable
private fun Heading(text: String) {
    Text(text, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
}

/** Vorschau einer Sortierung und, wenn schon etwas einsortiert wurde, der Knopf zum Zurücknehmen. */
@Composable
private fun SortStatus(preview: String, pending: Int, enabled: Boolean, onUndo: () -> Unit) {
    Dim(preview, size = 13)
    if (pending > 0) {
        OutlinedButton(onClick = onUndo, enabled = enabled) { Text("Zurücknehmen (${files(pending)})") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Dim(text: String, modifier: Modifier = Modifier, size: Int = 14) {
    Text(text, color = TextDim, fontSize = size.sp, modifier = modifier)
}

@Composable
private fun Panel(
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Die ganze Zeile schaltet, nicht nur der kleine Schalter.
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain)
            if (hint != null) Dim(hint, size = 12)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Wert aus einer festen Liste, mit − und + zum Blättern. */
@Composable
private fun Stepper(label: String, value: Int, options: List<Int>, format: (Int) -> String, onChange: (Int) -> Unit) {
    val i = options.indexOfFirst { it >= value }.let { if (it < 0) options.lastIndex else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f))
        StepButton("−", "$label: weniger", i > 0) { onChange(options[i - 1]) }
        Text(
            format(value),
            color = TextMain,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        StepButton("+", "$label: mehr", i < options.lastIndex) { onChange(options[i + 1]) }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(SurfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) TextMain else Track, fontSize = 20.sp)
    }
}

/** Kleines Vorschaubild für Bilder und Videos, sonst die Dateiendung. */
@Composable
private fun Thumb(file: File, name: String, sizeDp: Int) {
    val kind = Rules.kindOf(name)
    val bitmap by produceState<ImageBitmap?>(null, file.path, sizeDp) {
        value = withContext(Dispatchers.IO) { runCatching { loadThumb(file, kind, sizeDp * 3) }.getOrNull() }
    }
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.substringAfterLast('.', "?").take(4).uppercase(), color = TextDim, fontSize = 11.sp)
        }
    }
}

private fun loadThumb(file: File, kind: Kind, px: Int): ImageBitmap? = when (kind) {
    Kind.IMAGE -> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    Kind.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null).asImageBitmap()
    Kind.AUDIO, Kind.OTHER -> null
}

/** Raster aus neun Quadraten – das übliche Symbol für „Apps“ (nicht im Kern-Symbolsatz enthalten). */
private val AppsIcon: ImageVector by lazy {
    ImageVector.Builder("Apps", 24.dp, 24.dp, 24f, 24f).apply {
        listOf(4f, 10f, 16f).forEach { x ->
            listOf(4f, 10f, 16f).forEach { y ->
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(x, y), PathNode.HorizontalTo(x + 4f), PathNode.VerticalTo(y + 4f),
                        PathNode.HorizontalTo(x), PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                )
            }
        }
    }.build()
}
