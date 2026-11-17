package de.uekala.kehraus.core

import java.io.File

/** Die Aufräum-Regeln. Die Reihenfolge ist auch die Reihenfolge in der App. */
enum class RuleId(
    val title: String,
    val description: String,
    /** Ordner relativ zum Gerätespeicher, die diese Regel durchsucht. */
    val folders: List<String>,
    val defaultAgeDays: Int,
    /** 0 = jede Größe. */
    val defaultMinSizeMb: Int = 0,
    val kinds: Set<Kind> = Kind.entries.toSet(),
) {
    SCREENSHOTS(
        title = "Screenshots",
        description = "Bildschirmfotos und Bildschirmaufnahmen, die älter sind als die Frist.",
        folders = listOf(
            "Pictures/Screenshots", "DCIM/Screenshots",
            "DCIM/Screen recordings", "Movies/Screen recordings", "Movies/ScreenRecordings",
        ),
        defaultAgeDays = 30,
        kinds = setOf(Kind.IMAGE, Kind.VIDEO),
    ),
    WHATSAPP(
        title = "WhatsApp-Medien",
        description = "Empfangene und gesendete Bilder, Videos und GIFs. In den Chats sind sie danach nicht mehr zu öffnen.",
        folders = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs",
            "WhatsApp/Media/WhatsApp Images",
            "WhatsApp/Media/WhatsApp Video",
            "WhatsApp/Media/WhatsApp Animated Gifs",
        ),
        defaultAgeDays = 60,
        kinds = setOf(Kind.IMAGE, Kind.VIDEO),
    ),
    DOWNLOADS(
        title = "Downloads",
        description = "Alles im Download-Ordner, das seit der Frist nicht mehr geändert wurde.",
        folders = listOf("Download"),
        defaultAgeDays = 90,
    ),
    BIG_VIDEOS(
        title = "Große Videos",
        description = "Videos ab der Mindestgröße in Kamera-, Film- und Bilderordnern. Das trifft auch eigene Aufnahmen.",
        folders = listOf("DCIM", "Movies", "Pictures"),
        defaultAgeDays = 180,
        defaultMinSizeMb = 200,
        kinds = setOf(Kind.VIDEO),
    ),
}

enum class Kind { IMAGE, VIDEO, OTHER }

data class RuleConfig(
    val id: RuleId,
    val enabled: Boolean = false,
    val ageDays: Int = id.defaultAgeDays,
    val minSizeMb: Int = id.defaultMinSizeMb,
)

/** Eine Datei, die eine Regel getroffen hat. */
data class Hit(val file: File, val size: Long, val modified: Long)

object Rules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MB = 1024L * 1024

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")

    fun kindOf(name: String): Kind = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXT -> Kind.IMAGE
        in VIDEO_EXT -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /**
     * Alle Dateien unter [root], die [rule] zum Zeitpunkt [now] trifft.
     * Versteckte Dateien und Ordner (Punkt am Anfang) und alles unter [exclude] bleiben unberührt.
     * Ob die Regel eingeschaltet ist, spielt hier keine Rolle – so funktioniert auch die Vorschau.
     */
    fun find(root: File, rule: RuleConfig, now: Long, exclude: File? = null): List<Hit> {
        val cutoff = now - rule.ageDays.coerceAtLeast(1) * DAY_MS
        val minSize = rule.minSizeMb.coerceAtLeast(0) * MB
        val excluded = exclude?.absoluteFile
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        for (folder in rule.id.folders) {
            val dir = File(root, folder)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .onEnter { d -> (d == dir || !d.name.startsWith(".")) && d.absoluteFile != excluded }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { f ->
                    if (kindOf(f.name) !in rule.id.kinds) return@forEach
                    val modified = f.lastModified()
                    val size = f.length()
                    // lastModified() liefert 0, wenn die Datei nicht lesbar ist – dann lieber Finger weg.
                    if (modified <= 0L || modified > cutoff || size < minSize) return@forEach
                    if (seen.add(f.absolutePath)) hits.add(Hit(f, size, modified))
                }
        }
        return hits
    }
}
