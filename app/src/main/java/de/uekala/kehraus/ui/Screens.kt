package de.uekala.kehraus.ui

import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uekala.kehraus.MainViewModel
import de.uekala.kehraus.MainViewModel.Companion.files
import de.uekala.kehraus.core.Kind
import de.uekala.kehraus.core.RuleConfig
import de.uekala.kehraus.core.RuleId
import de.uekala.kehraus.core.Rules
import de.uekala.kehraus.core.TrashEntry
import de.uekala.kehraus.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AGE_OPTIONS = listOf(7, 14, 30, 60, 90, 180, 365)
private val RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90)
private val SIZE_OPTIONS = listOf(50, 100, 200, 500, 1000)

private fun days(n: Int): String = when (n) {
    1 -> "1 Tag"
    365 -> "1 Jahr"
    else -> "$n Tage"
}

private fun megabytes(n: Int): String = if (n >= 1000) "${n / 1000} GB" else "$n MB"

private fun dateTime(ms: Long): String = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(ms))

@Composable
fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.message = null
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                val tabs = listOf("Übersicht" to Icons.Filled.Home, "Regeln" to Icons.Filled.Settings, "Papierkorb" to Icons.Filled.Delete)
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> Overview(vm, onOpenRules = { tab = 1 })
                1 -> RulesScreen(vm)
                else -> TrashScreen(vm)
            }
        }
    }
}

// ---------- Übersicht ----------

@Composable
private fun Overview(vm: MainViewModel, onOpenRules: () -> Unit) {
    val ctx = LocalContext.current
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
                )
            }
        }

        Panel("Aufräumen") {
            val anyEnabled = vm.rules.any { it.enabled }
            val last = vm.lastRun
            if (last == null) {
                Dim("Noch kein Lauf.")
            } else {
                KeyValue("Letzter Lauf", dateTime(last.at))
                KeyValue(
                    "Verschoben",
                    if (last.moved == 0) "nichts" else "${files(last.moved)} · ${vm.size(last.bytes)}",
                )
                if (last.failed > 0) {
                    Text("${files(last.failed)} ließen sich nicht verschieben.", color = Warn, fontSize = 13.sp)
                }
            }
            Button(
                onClick = { vm.runNow() },
                enabled = vm.hasAccess && anyEnabled && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.busy) "Räumt auf …" else "Jetzt aufräumen") }
            if (!anyEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dim("Noch ist keine Regel eingeschaltet.", Modifier.weight(1f))
                    TextButton(onClick = onOpenRules) { Text("Regeln") }
                }
            }
        }

        Panel("Papierkorb") {
            KeyValue("Wartet auf Ablauf der Frist", "${files(vm.trash.size)} · ${vm.size(vm.trashBytes)}")
            KeyValue("Bisher endgültig gelöscht", vm.size(vm.totalFreed))
            Dim("Platz wird erst frei, wenn die Frist abgelaufen ist oder du den Papierkorb leerst.", size = 12)
        }

        Panel("Automatik") {
            SwitchRow("Täglich aufräumen", "Einmal am Tag im Hintergrund, nicht bei schwachem Akku.", vm.auto) { vm.updateAuto(it) }
            SwitchRow("Melden, wenn etwas passiert ist", null, vm.notify) { vm.updateNotify(it) }
        }
    }
}

// ---------- Regeln ----------

@Composable
private fun RulesScreen(vm: MainViewModel) {
    var showHits by remember { mutableStateOf<RuleId?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("Regeln")
        Dim("Eingeschaltete Regeln verschieben ihre Treffer in den Papierkorb – beim täglichen Lauf oder über „Jetzt aufräumen“.")
        vm.rules.forEach { rule -> RuleCard(vm, rule) { showHits = rule.id } }
    }
    showHits?.let { id -> HitsDialog(vm, id) { showHits = null } }
}

@Composable
private fun RuleCard(vm: MainViewModel, rule: RuleConfig, onShow: () -> Unit) {
    Panel(
        title = rule.id.title,
        trailing = { Switch(checked = rule.enabled, onCheckedChange = { vm.setRule(rule.copy(enabled = it)) }) },
    ) {
        Dim(rule.id.description, size = 13)
        Stepper("Älter als", rule.ageDays, AGE_OPTIONS, ::days) { vm.setRule(rule.copy(ageDays = it)) }
        if (rule.id.defaultMinSizeMb > 0) {
            Stepper("Ab Größe", rule.minSizeMb, SIZE_OPTIONS, ::megabytes) { vm.setRule(rule.copy(minSizeMb = it)) }
        }
        val p = vm.previews[rule.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !vm.hasAccess -> "Vorschau erst mit Dateizugriff"
                    p == null -> "Wird gezählt …"
                    p.count == 0 -> "Trifft im Moment nichts"
                    else -> "Trifft jetzt ${files(p.count)} · ${vm.size(p.bytes)}"
                },
                color = if (p != null && p.count > 0) Accent else TextDim,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (p != null && p.count > 0) TextButton(onClick = onShow) { Text("Ansehen") }
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
        title = { Text(id.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(p?.hits.orEmpty()) { hit ->
                    Column {
                        Text(hit.file.name, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val age = ((now - hit.modified) / Rules.DAY_MS).toInt()
                        Dim("${vm.size(hit.size)} · ${days(age)} alt · ${hit.file.parentFile?.name.orEmpty()}", size = 12)
                    }
                }
                if (p != null && p.count > p.hits.size) {
                    item { Dim("… und ${p.count - p.hits.size} weitere", size = 12) }
                }
            }
        },
    )
}

// ---------- Papierkorb ----------

@Composable
private fun TrashScreen(vm: MainViewModel) {
    var selected by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val store = remember { Storage.trash() }
    val now = remember(vm.trash) { System.currentTimeMillis() }

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
                Stepper("Endgültig löschen nach", vm.retentionDays, RETENTION_OPTIONS, ::days) { vm.updateRetention(it) }
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
                    Dim("${folderOf(e)} · ${vm.size(e.size)} · ${remaining(e, vm.retentionDays, now)}", size = 12)
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
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.restore(listOf(e.id)); selected = null }) { Text("Zurücklegen") }
            },
            dismissButton = {
                TextButton(onClick = { vm.delete(listOf(e.id)); selected = null }) { Text("Endgültig löschen", color = Bad) }
            },
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

private fun ruleTitle(name: String): String = RuleId.entries.firstOrNull { it.name == name }?.title ?: name

private fun remaining(e: TrashEntry, retentionDays: Int, now: Long): String {
    val left = e.expiresAt(retentionDays) - now
    if (left <= 0) return "wird beim nächsten Lauf gelöscht"
    val d = ((left + Rules.DAY_MS - 1) / Rules.DAY_MS).toInt()
    return "noch ${days(d)}"
}

// ---------- Bausteine ----------

@Composable
private fun Heading(text: String) {
    Text(text, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain)
            if (hint != null) Dim(hint, size = 12)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Wert aus einer festen Liste, mit − und + zum Blättern. */
@Composable
private fun Stepper(label: String, value: Int, options: List<Int>, format: (Int) -> String, onChange: (Int) -> Unit) {
    val i = options.indexOfFirst { it >= value }.let { if (it < 0) options.lastIndex else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f))
        StepButton("−", i > 0) { onChange(options[i - 1]) }
        Text(
            format(value),
            color = TextMain,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        StepButton("+", i < options.lastIndex) { onChange(options[i + 1]) }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(SurfaceHigh).clickable(enabled = enabled, onClick = onClick),
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
    Kind.OTHER -> null
}
