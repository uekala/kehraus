package de.uekala.kehraus.core

import java.io.File

enum class Kind { IMAGE, VIDEO, AUDIO, OTHER }

/** Überschriften, unter denen die Regeln in der App stehen. */
enum class Group(val title: String) {
    MEDIA("Bilder und Videos"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram"),
    FILES("Dateien"),
}

/** Die Aufräum-Regeln. Die Reihenfolge ist auch die Reihenfolge in der App und im Lauf. */
enum class RuleId(
    val group: Group,
    val title: String,
    val description: String,
    /** Ordner relativ zum Gerätespeicher, die diese Regel durchsucht. */
    val folders: List<String>,
    val defaultAgeDays: Int,
    /** 0 = jede Größe. */
    val defaultMinSizeMb: Int = 0,
    val kinds: Set<Kind> = Kind.entries.toSet(),
    /** Wenn gesetzt, zählen nur diese Dateiendungen (klein geschrieben). */
    val extensions: Set<String> = emptySet(),
    /** Wenn gesetzt, zählen nur Dateien, in deren Pfad ein Ordner mit diesem Namen vorkommt. */
    val insideFolder: String? = null,
) {
    SCREENSHOTS(
        Group.MEDIA, "Screenshots",
        "Bildschirmfotos und Bildschirmaufnahmen, die älter sind als die Frist.",
        listOf(
            "Pictures/Screenshots", "DCIM/Screenshots",
            "DCIM/Screen recordings", "Movies/Screen recordings", "Movies/ScreenRecordings",
        ),
        defaultAgeDays = 30,
        kinds = setOf(Kind.IMAGE, Kind.VIDEO),
    ),
    BIG_VIDEOS(
        Group.MEDIA, "Große Videos",
        "Videos ab der Mindestgröße in Kamera-, Film- und Bilderordnern. Das trifft auch eigene Aufnahmen.",
        listOf("DCIM", "Movies", "Pictures"),
        defaultAgeDays = 180,
        defaultMinSizeMb = 200,
        kinds = setOf(Kind.VIDEO),
    ),
    WHATSAPP(
        Group.WHATSAPP, "Bilder und Videos",
        "Empfangene und gesendete Bilder, Videos und GIFs. In den Chats sind sie danach nicht mehr zu öffnen.",
        whatsapp("WhatsApp Images", "WhatsApp Video", "WhatsApp Animated Gifs"),
        defaultAgeDays = 60,
        kinds = setOf(Kind.IMAGE, Kind.VIDEO),
    ),
    WHATSAPP_SENT(
        Group.WHATSAPP, "Gesendete Kopien",
        "Was du selbst verschickt hast, legt WhatsApp noch einmal ab. Das Original bleibt, wo es war.",
        whatsapp("WhatsApp Images", "WhatsApp Video", "WhatsApp Animated Gifs", "WhatsApp Documents", "WhatsApp Audio"),
        defaultAgeDays = 14,
        insideFolder = "Sent",
    ),
    WHATSAPP_VOICE(
        Group.WHATSAPP, "Sprachnachrichten und Audio",
        "Sprachnachrichten und Audiodateien aus den Chats.",
        whatsapp("WhatsApp Voice Notes", "WhatsApp Audio"),
        defaultAgeDays = 90,
        kinds = setOf(Kind.AUDIO),
    ),
    WHATSAPP_DOCS(
        Group.WHATSAPP, "Dokumente",
        "PDFs und andere Dateien, die über WhatsApp gekommen sind.",
        whatsapp("WhatsApp Documents"),
        defaultAgeDays = 180,
    ),
    TELEGRAM(
        Group.TELEGRAM, "Telegram-Dateien",
        "Bilder, Videos, Audio und Dokumente, die Telegram im Gerätespeicher abgelegt hat.",
        listOf(
            "Android/media/org.telegram.messenger/Telegram",
            "Telegram", "Pictures/Telegram", "Movies/Telegram", "Download/Telegram", "Music/Telegram",
        ),
        defaultAgeDays = 60,
    ),
    APKS(
        Group.FILES, "Installationsdateien",
        "APK-Dateien im Download-Ordner. Nach der Installation werden sie nicht mehr gebraucht.",
        listOf("Download"),
        defaultAgeDays = 7,
        extensions = setOf("apk", "xapk", "apks", "apkm"),
    ),
    DOWNLOADS(
        Group.FILES, "Downloads",
        "Alles im Download-Ordner, das seit der Frist nicht mehr geändert wurde.",
        listOf("Download"),
        defaultAgeDays = 90,
    ),
}

private fun whatsapp(vararg media: String): List<String> =
    listOf("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media").flatMap { base -> media.map { "$base/$it" } }

data class RuleConfig(
    val id: RuleId,
    val enabled: Boolean = false,
    val ageDays: Int = id.defaultAgeDays,
    val minSizeMb: Int = id.defaultMinSizeMb,
)

/** Eine Datei, die eine Regel getroffen hat. */
data class Hit(val file: File, val size: Long, val modified: Long)

/** Was keine Regel anfassen darf: ganze Ordner (mit allem darunter) und einzelne Dateien, als volle Pfade. */
data class Protection(val folders: Set<String> = emptySet(), val files: Set<String> = emptySet()) {
    fun covers(file: File): Boolean {
        val path = file.absolutePath
        return path in files || coversFolder(file.parentFile)
    }

    fun coversFolder(dir: File?): Boolean {
        if (folders.isEmpty()) return false
        var d = dir
        while (d != null) {
            if (d.absolutePath in folders) return true
            d = d.parentFile
        }
        return false
    }
}

object Rules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MB = 1024L * 1024

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")
    private val AUDIO_EXT = setOf("opus", "ogg", "mp3", "m4a", "aac", "wav", "amr", "flac")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): Kind = when (extensionOf(name)) {
        in IMAGE_EXT -> Kind.IMAGE
        in VIDEO_EXT -> Kind.VIDEO
        in AUDIO_EXT -> Kind.AUDIO
        else -> Kind.OTHER
    }

    /**
     * Alle Dateien unter [root], die [rule] zum Zeitpunkt [now] trifft.
     * Versteckte Dateien und Ordner (Punkt am Anfang), alles unter [exclude] und alles auf der
     * Schutzliste bleiben unberührt. Ob die Regel eingeschaltet ist, spielt hier keine Rolle –
     * so funktioniert auch die Vorschau.
     */
    fun find(
        root: File,
        rule: RuleConfig,
        now: Long,
        exclude: File? = null,
        protection: Protection = Protection(),
    ): List<Hit> {
        val id = rule.id
        val cutoff = now - rule.ageDays.coerceAtLeast(1) * DAY_MS
        val minSize = rule.minSizeMb.coerceAtLeast(0) * MB
        val excluded = exclude?.absoluteFile
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        for (folder in id.folders) {
            val dir = File(root, folder)
            if (!dir.isDirectory || protection.coversFolder(dir)) continue
            dir.walkTopDown()
                .onEnter { d ->
                    (d == dir || !d.name.startsWith(".")) &&
                        d.absoluteFile != excluded &&
                        d.absolutePath !in protection.folders
                }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { f ->
                    if (kindOf(f.name) !in id.kinds) return@forEach
                    if (id.extensions.isNotEmpty() && extensionOf(f.name) !in id.extensions) return@forEach
                    if (id.insideFolder != null && !isInside(f, dir, id.insideFolder)) return@forEach
                    if (f.absolutePath in protection.files) return@forEach
                    val modified = f.lastModified()
                    val size = f.length()
                    // lastModified() liefert 0, wenn die Datei nicht lesbar ist – dann lieber Finger weg.
                    if (modified <= 0L || modified > cutoff || size < minSize) return@forEach
                    if (seen.add(f.absolutePath)) hits.add(Hit(f, size, modified))
                }
        }
        return hits
    }

    /** Liegt [file] unterhalb von [base] in einem Ordner namens [folderName]? */
    private fun isInside(file: File, base: File, folderName: String): Boolean {
        var d = file.parentFile
        while (d != null && d != base) {
            if (d.name == folderName) return true
            d = d.parentFile
        }
        return false
    }
}
