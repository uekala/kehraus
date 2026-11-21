package de.uekala.kehraus.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Teilt Screenshots in Monatsordner auf (z. B. `Pictures/Screenshots/2026-11`). Der laufende Monat bleibt liegen. */
object ScreenshotSorter {
    val BASES = listOf("Pictures/Screenshots", "DCIM/Screenshots")
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    fun plan(root: File, now: Long, protection: Protection = Protection(), zone: ZoneId = ZoneId.systemDefault()): List<Move> {
        val current = MONTH.format(Instant.ofEpochMilli(now).atZone(zone))
        return BASES.flatMap { base ->
            val dir = File(root, base)
            if (!dir.isDirectory || protection.coversFolder(dir)) return@flatMap emptyList()
            dir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.startsWith(".") && Rules.kindOf(it.name) in setOf(Kind.IMAGE, Kind.VIDEO) }
                .filter { it.absolutePath !in protection.files && it.lastModified() > 0 && '\t' !in it.name && '\n' !in it.name }
                .mapNotNull { f ->
                    val month = MONTH.format(Instant.ofEpochMilli(f.lastModified()).atZone(zone))
                    if (month == current) null else Move(f.absolutePath, File(File(dir, month), f.name).absolutePath)
                }
        }.sortedBy { it.from }
    }
}

/** Erkennt an erkanntem Text, ob ein Foto ein Beleg, Brief oder sonstiges Dokument ist. */
object DocClassifier {
    private val KEYWORDS = listOf(
        "rechnung", "rechnungsnummer", "quittung", "kassenbon", "beleg", "bon", "summe", "zwischensumme", "gesamt",
        "gesamtbetrag", "betrag", "mwst", "ust", "netto", "brutto", "steuer", "steuernummer", "iban", "bic",
        "kundennummer", "vertragsnummer", "zahlung", "zahlbetrag", "kartenzahlung", "bar", "rückgeld", "total",
        "invoice", "receipt", "vertrag", "bescheid", "versicherung", "mahnung", "kündigung", "anschrift",
        "sehr geehrte", "sehr geehrter", "mit freundlichen grüßen", "unterschrift", "datum", "seite",
    ).map { Regex("(?<![\\p{L}])" + Regex.escape(it) + "(?![\\p{L}])") }

    private val WORD = Regex("\\p{L}{2,}")
    private val PRICE = Regex("\\d+[.,]\\d{2}(?!\\d)")

    fun isDocument(text: String): Boolean {
        val t = text.lowercase()
        val words = WORD.findAll(t).count()
        val keywords = KEYWORDS.count { it.containsMatchIn(t) } + if ('€' in t) 1 else 0
        val prices = PRICE.findAll(t).count()
        return (keywords >= 2 && words >= 12) || (prices >= 3 && words >= 8) || words >= 90
    }
}

/** Ergebnis der Texterkennung je Foto: Pfad, Änderungszeit beim Prüfen, Dokument ja/nein. */
data class DocVerdict(val path: String, val modified: Long, val document: Boolean)

/** Merkt sich, welche Fotos schon geprüft sind, damit jedes nur einmal durch die Texterkennung muss. */
class DocCache(private val file: File) {
    fun load(): Map<String, DocVerdict> =
        if (!file.isFile) emptyMap()
        else file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            val modified = p[0].toLongOrNull() ?: return@mapNotNull null
            DocVerdict(p[2], modified, p[1] == "1")
        }.associateBy { it.path }

    fun add(verdicts: List<DocVerdict>) {
        if (verdicts.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(
            verdicts.joinToString("") { "${it.modified}\t${if (it.document) 1 else 0}\t${it.path}\n" },
            Charsets.UTF_8,
        )
    }
}

/** Belege und Dokumentfotos aus Kamera und WhatsApp in einen eigenen Ordner legen. */
object Documents {
    const val TARGET = "Pictures/Dokumente"
    val SOURCES = listOf(
        "DCIM/Camera",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "WhatsApp/Media/WhatsApp Images",
    )
    private val EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    /** Fotos direkt in den Quellordnern, mindestens einen Tag alt – frische Fotos bleiben in Ruhe. */
    private fun photos(root: File, now: Long, protection: Protection): List<File> = SOURCES.flatMap { src ->
        val dir = File(root, src)
        if (!dir.isDirectory || protection.coversFolder(dir)) return@flatMap emptyList()
        dir.listFiles().orEmpty().filter { f ->
            f.isFile && !f.name.startsWith(".") && Rules.extensionOf(f.name) in EXT &&
                f.absolutePath !in protection.files && '\t' !in f.name && '\n' !in f.name &&
                f.lastModified() in 1..(now - Rules.DAY_MS)
        }
    }

    /** Fotos, die noch nicht (oder nicht in dieser Fassung) geprüft wurden, neueste zuerst. */
    fun candidates(root: File, now: Long, cache: Map<String, DocVerdict>, protection: Protection = Protection()): List<File> =
        photos(root, now, protection)
            .filter { f -> cache[f.absolutePath]?.modified != f.lastModified() }
            .sortedByDescending { it.lastModified() }

    /** Was als Dokument erkannt wurde und noch an seinem alten Platz liegt. */
    fun plan(root: File, now: Long, cache: Map<String, DocVerdict>, protection: Protection = Protection()): List<Move> {
        val target = File(root, TARGET)
        return photos(root, now, protection)
            .filter { f -> cache[f.absolutePath]?.let { it.document && it.modified == f.lastModified() } == true }
            .map { Move(it.absolutePath, File(target, it.name).absolutePath) }
            .sortedBy { it.from }
    }
}

/** Legt einzelne Bilder aus WhatsApp in ein eigenes Album, damit keine Regel sie mehr empfiehlt. */
object Keeper {
    const val TARGET = "Pictures/WhatsApp behalten"

    fun keep(root: File, file: File): File? {
        if (!file.isFile) return null
        val target = Journal.free(File(File(root, TARGET), file.name))
        target.parentFile?.mkdirs()
        return if (file.renameTo(target)) target else null
    }
}
