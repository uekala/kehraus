package de.uekala.kehraus.core

import java.io.File
import java.security.MessageDigest

/** Grobe Dateiarten für die Speicher-Analyse. */
enum class Category(val title: String) {
    IMAGE("Bilder"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Dokumente"),
    ARCHIVE("Archive"),
    APK("Installationsdateien"),
    OTHER("Sonstiges");

    companion object {
        private val DOCUMENT_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "csv", "epub")
        private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        private val APK_EXT = setOf("apk", "xapk", "apks", "apkm")

        fun of(name: String): Category = when (Rules.kindOf(name)) {
            Kind.IMAGE -> IMAGE
            Kind.VIDEO -> VIDEO
            Kind.AUDIO -> AUDIO
            Kind.OTHER -> when (Rules.extensionOf(name)) {
                in DOCUMENT_EXT -> DOCUMENT
                in ARCHIVE_EXT -> ARCHIVE
                in APK_EXT -> APK
                else -> OTHER
            }
        }
    }
}

data class FileInfo(val path: String, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

data class Usage(val bytes: Long, val files: Int)

/** Ergebnis einer Speicher-Analyse. Alle Pfade sind volle Pfade. */
class Report(
    val root: String,
    val files: List<FileInfo>,
    /** Verbrauch je Ordner, jeweils mit allem darunter. */
    val folders: Map<String, Usage>,
    val byCategory: Map<Category, Usage>,
    val largest: List<FileInfo>,
) {
    val total: Usage get() = folders[root] ?: Usage(0, 0)

    private val childFolders: Map<String, List<String>> by lazy {
        folders.keys.filter { it != root }.groupBy { it.substringBeforeLast('/', "") }
    }

    /** Unterordner von [dir], größter zuerst. */
    fun subfolders(dir: String): List<Pair<String, Usage>> =
        childFolders[dir].orEmpty().map { it to folders.getValue(it) }.sortedByDescending { it.second.bytes }

    /** Dateien direkt in [dir], größte zuerst. */
    fun filesIn(dir: String, limit: Int = 50): List<FileInfo> =
        files.filter { it.parent == dir }.sortedByDescending { it.size }.take(limit)
}

object Analysis {
    /** Für andere Apps gesperrt; dort kann Kehraus weder lesen noch aufräumen. */
    private val BLOCKED = listOf("Android/data", "Android/obb")

    /**
     * Geht den ganzen Gerätespeicher unter [root] durch – auch versteckte Dateien, denn die belegen ebenfalls Platz.
     * Der Papierkorb ([exclude]) zählt nicht mit.
     */
    fun scan(root: File, exclude: File? = null, largestCount: Int = 100): Report {
        val rootPath = root.absolutePath
        val skip = (BLOCKED.map { File(root, it).absolutePath } + listOfNotNull(exclude?.absolutePath)).toSet()
        val files = ArrayList<FileInfo>()
        val folderBytes = HashMap<String, Long>()
        val folderFiles = HashMap<String, Int>()
        val catBytes = HashMap<Category, Long>()
        val catFiles = HashMap<Category, Int>()
        folderBytes[rootPath] = 0
        folderFiles[rootPath] = 0

        root.walkTopDown().onEnter { it.absolutePath !in skip }.forEach { f ->
            if (f.isDirectory) {
                folderBytes.putIfAbsent(f.absolutePath, 0)
                folderFiles.putIfAbsent(f.absolutePath, 0)
                return@forEach
            }
            if (!f.isFile) return@forEach
            val size = f.length()
            val info = FileInfo(f.absolutePath, size, f.lastModified())
            files.add(info)
            val cat = Category.of(f.name)
            catBytes[cat] = (catBytes[cat] ?: 0) + size
            catFiles[cat] = (catFiles[cat] ?: 0) + 1
            var dir = info.parent
            while (dir.length >= rootPath.length) {
                folderBytes[dir] = (folderBytes[dir] ?: 0) + size
                folderFiles[dir] = (folderFiles[dir] ?: 0) + 1
                if (dir == rootPath) break
                dir = dir.substringBeforeLast('/', "")
            }
        }
        return Report(
            root = rootPath,
            files = files,
            folders = folderBytes.mapValues { (k, v) -> Usage(v, folderFiles[k] ?: 0) },
            byCategory = catBytes.mapValues { (k, v) -> Usage(v, catFiles[k] ?: 0) },
            largest = files.sortedByDescending { it.size }.take(largestCount),
        )
    }
}

/** Dateien mit exakt gleichem Inhalt: [keep] bleibt, [copies] können weg. */
data class DupGroup(val size: Long, val keep: FileInfo, val copies: List<FileInfo>) {
    val wasted: Long get() = size * copies.size
}

object Duplicates {
    private const val HEAD = 64 * 1024

    /**
     * Sucht unter den [files] Gruppen mit gleichem Inhalt. Verglichen wird erst die Größe, dann der Anfang,
     * dann die ganze Datei – so werden nur echte Kandidaten vollständig gelesen.
     *
     * Nicht angefasst werden versteckte Dateien, Ordner anderer Apps unter `Android/` (außer WhatsApp und
     * Telegram) und alles auf der Schutzliste: Solche Dateien können das Original sein, aber nie die Kopie.
     */
    fun find(
        files: List<FileInfo>,
        root: String,
        protection: Protection = Protection(),
        minSize: Long = 16 * 1024,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DupGroup> {
        val bySize = files.filter { it.size >= minSize && eligible(relative(it.path, root)) }
            .groupBy { it.size }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val groups = ArrayList<DupGroup>()
        for (sameSize in bySize) {
            val sameHead = sameSize.groupBy { hash(it.path, HEAD.toLong()) }.filter { it.key != null && it.value.size > 1 }
            for (candidates in sameHead.values) {
                val identical = if (candidates.first().size <= HEAD) listOf(candidates)
                else candidates.groupBy { hash(it.path, Long.MAX_VALUE) }.filter { it.key != null && it.value.size > 1 }.values
                for (same in identical) {
                    val keep = same.sortedWith(
                        compareBy<FileInfo> { rank(relative(it.path, root)) }.thenBy { it.modified }.thenBy { it.path.length }.thenBy { it.path }
                    ).first()
                    val copies = same.filter { it !== keep && !protection.covers(File(it.path)) }
                    if (copies.isNotEmpty()) groups.add(DupGroup(keep.size, keep, copies.sortedBy { it.path }))
                }
            }
            done += sameSize.size
            onProgress(done, total)
        }
        return groups.sortedByDescending { it.wasted }
    }

    private fun relative(path: String, root: String): String = path.removePrefix(root).trimStart('/')

    internal fun eligible(rel: String): Boolean {
        if (rel.split('/').any { it.startsWith(".") }) return false
        if (!rel.startsWith("Android/")) return true
        return rel.startsWith("Android/media/com.whatsapp/") || rel.startsWith("Android/media/org.telegram.messenger/")
    }

    /** Je kleiner, desto eher ist das der Platz, an dem die Datei bleiben soll. */
    internal fun rank(rel: String): Int {
        val p = rel.lowercase()
        return when {
            "/whatsapp/" in "/$p" && "/sent/" in p -> 9
            "/whatsapp/" in "/$p" -> 8
            "telegram" in p -> 7
            p.startsWith("pictures/screenshots/") || p.startsWith("dcim/screenshots/") -> 6
            p.startsWith("download/") -> 5
            p.startsWith("dcim/camera/") -> 0
            p.startsWith("dcim/") -> 1
            p.startsWith("pictures/") -> 2
            p.startsWith("documents/") || p.startsWith("movies/") || p.startsWith("music/") -> 3
            else -> 4
        }
    }

    /** SHA-256 über die ersten [limit] Bytes; null, wenn die Datei nicht lesbar ist. */
    private fun hash(path: String, limit: Long): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = limit
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buffer, 0, n)
                left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}
