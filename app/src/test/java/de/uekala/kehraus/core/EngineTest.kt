package de.uekala.kehraus.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EngineTest {
    private lateinit var root: File
    private lateinit var trash: Trash
    private lateinit var engine: Engine
    private val now = 1_800_000_000_000L
    private val day = Rules.DAY_MS

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kehraus").toFile()
        trash = Trash(File(root, "Kehraus/Papierkorb"))
        engine = Engine(root, trash)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, ageDays: Int, bytes: Int = 10): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes))
        assertTrue(f.setLastModified(now - ageDays * day))
        return f
    }

    private fun Engine.run(rules: List<RuleConfig>, retention: Int, at: Long) =
        run(rules, RunOptions(retentionDays = retention), at)

    private fun on(id: RuleId, ageDays: Int = id.defaultAgeDays, minSizeMb: Int = id.defaultMinSizeMb) =
        RuleConfig(id, enabled = true, ageDays = ageDays, minSizeMb = minSizeMb)

    @Test
    fun `nur alte Screenshots werden verschoben`() {
        val old = file("Pictures/Screenshots/alt.png", 31)
        val fresh = file("Pictures/Screenshots/neu.png", 29)
        val camera = file("DCIM/Camera/foto.jpg", 400)

        val result = engine.run(listOf(on(RuleId.SCREENSHOTS)), 30, now)

        assertEquals(1, result.moved)
        assertEquals(10L, result.movedBytes)
        assertEquals(listOf(old.absolutePath), result.changedPaths)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(camera.exists())
        assertEquals(1, trash.entries().size)
    }

    @Test
    fun `ausgeschaltete Regeln tun nichts`() {
        val old = file("Pictures/Screenshots/alt.png", 300)
        val result = engine.run(listOf(RuleConfig(RuleId.SCREENSHOTS, enabled = false)), 30, now)
        assertEquals(0, result.moved)
        assertTrue(old.exists())
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `Vorschau zeigt Treffer auch bei ausgeschalteter Regel und verschiebt nichts`() {
        val old = file("Download/rechnung.pdf", 120)
        val hits = engine.preview(RuleConfig(RuleId.DOWNLOADS, enabled = false), now)
        assertEquals(listOf(old.absolutePath), hits.map { it.file.absolutePath })
        assertTrue(old.exists())
    }

    @Test
    fun `WhatsApp trifft nur Bilder und Videos auch in Unterordnern`() {
        val base = "Android/media/com.whatsapp/WhatsApp/Media"
        val img = file("$base/WhatsApp Images/IMG-1.jpg", 90)
        val sent = file("$base/WhatsApp Images/Sent/IMG-2.jpg", 90)
        val vid = file("$base/WhatsApp Video/VID-1.mp4", 90)
        val doc = file("$base/WhatsApp Documents/vertrag.pdf", 900)
        val nomedia = file("$base/WhatsApp Images/Sent/.nomedia", 900)
        val hiddenDir = file("$base/WhatsApp Images/.Statuses/status.jpg", 900)

        val result = engine.run(listOf(on(RuleId.WHATSAPP)), 30, now)

        assertEquals(3, result.moved)
        assertFalse(img.exists() || sent.exists() || vid.exists())
        assertTrue(doc.exists())
        assertTrue(nomedia.exists())
        assertTrue(hiddenDir.exists())
    }

    @Test
    fun `große Videos brauchen Mindestgröße und Alter und lassen Bilder in Ruhe`() {
        val big = file("DCIM/Camera/lang.mp4", 200, bytes = 2 * 1024 * 1024)
        val small = file("DCIM/Camera/kurz.mp4", 200, bytes = 100)
        val young = file("Movies/neu.mp4", 5, bytes = 2 * 1024 * 1024)
        val photo = file("DCIM/Camera/riesig.jpg", 200, bytes = 2 * 1024 * 1024)

        val result = engine.run(listOf(on(RuleId.BIG_VIDEOS, ageDays = 180, minSizeMb = 1)), 30, now)

        assertEquals(1, result.moved)
        assertFalse(big.exists())
        assertTrue(small.exists() && young.exists() && photo.exists())
    }

    @Test
    fun `der Papierkorb selbst wird nie durchsucht`() {
        // Papierkorb absichtlich mitten in einem durchsuchten Ordner.
        val inside = Trash(File(root, "Download/Papierkorb"))
        val e = Engine(root, inside)
        val old = file("Download/alt.zip", 500)
        e.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        assertFalse(old.exists())

        val again = e.run(RuleId.entries.map { on(it, ageDays = 1, minSizeMb = 0) }, 30, now)

        assertEquals(0, again.moved)
        assertEquals(1, inside.entries().size)
    }

    @Test
    fun `Wiederherstellen legt die Datei an den alten Platz`() {
        val f = file("Download/wichtig.pdf", 100, bytes = 42)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        val entry = trash.entries().single()
        assertEquals("wichtig.pdf", entry.name)
        assertEquals(42L, entry.size)
        assertEquals("DOWNLOADS", entry.rule)

        val restored = trash.restore(entry.id)

        assertEquals(f.absolutePath, restored?.absolutePath)
        assertEquals(42L, f.length())
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `Wiederherstellen überschreibt nichts`() {
        val f = file("Download/liste.txt", 100)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        f.writeText("neue Datei mit gleichem Namen")

        val restored = trash.restore(trash.entries().single().id)

        assertEquals("liste (wiederhergestellt).txt", restored?.name)
        assertEquals("neue Datei mit gleichem Namen", f.readText())
    }

    @Test
    fun `endgültig gelöscht wird erst nach Ablauf der Frist`() {
        file("Download/a.bin", 100, bytes = 7)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        val stored = trash.fileOf(trash.entries().single())

        val early = engine.run(emptyList(), 30, now + 29 * day)
        assertEquals(Freed(0, 0), early.purged)
        assertTrue(stored.exists())

        val late = engine.run(emptyList(), 30, now + 30 * day)
        assertEquals(Freed(1, 7), late.purged)
        assertFalse(stored.exists())
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `eine zweite Instanz liest denselben Papierkorb`() {
        file("Download/a.bin", 100)
        file("Download/b.bin", 100)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)

        val other = Trash(File(root, "Kehraus/Papierkorb"))
        assertEquals(2, other.entries().size)
        assertEquals(Freed(2, 20), other.delete(other.entries().map { it.id }))
        assertTrue(trash.entries().isEmpty())
    }

    @Test
    fun `Eintrag ohne Datei wird aussortiert und kaputte Zeilen stören nicht`() {
        file("Download/a.bin", 100)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        val index = File(trash.dir, "index.tsv")
        index.appendText("kaputt\n")
        index.appendText(Trash.format(TrashEntry("verwaist", "/x/y.jpg", now, 1, "DOWNLOADS")) + "\n")

        assertEquals(1, trash.entries().size)
        assertNull(trash.restore("verwaist"))
    }

    @Test
    fun `Pfade mit Sonderzeichen überstehen die Liste`() {
        val e = TrashEntry("id", "/sdcard/Download/a\tb\\c\nd.pdf", 5, 6, "DOWNLOADS")
        assertEquals(e, Trash.parse(Trash.format(e)))
        val f = file("Download/Urlaub (2) – Fotos & Öl.zip", 100)
        engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now)
        assertNotNull(trash.restore(trash.entries().single().id))
        assertTrue(f.exists())
    }

    @Test
    fun `Papierkorb enthält nomedia damit die Galerie ihn nicht zeigt`() {
        file("Pictures/Screenshots/alt.png", 100)
        engine.run(listOf(on(RuleId.SCREENSHOTS)), 30, now)
        assertTrue(File(trash.dir, ".nomedia").exists())
    }

    @Test
    fun `Dateityp wird an der Endung erkannt`() {
        assertEquals(Kind.IMAGE, Rules.kindOf("IMG.JPG"))
        assertEquals(Kind.VIDEO, Rules.kindOf("clip.mp4"))
        assertEquals(Kind.AUDIO, Rules.kindOf("PTT-1.opus"))
        assertEquals(Kind.OTHER, Rules.kindOf("ohne-endung"))
        assertEquals(Kind.OTHER, Rules.kindOf("archiv.tar.gz"))
    }

    // ---------- Paket A ----------

    private val wa = "Android/media/com.whatsapp/WhatsApp/Media"

    @Test
    fun `gesendete Kopien trifft nur den Ordner Sent`() {
        val sent = file("$wa/WhatsApp Images/Sent/IMG-1.jpg", 20)
        val sentDoc = file("$wa/WhatsApp Documents/Sent/angebot.pdf", 20)
        val received = file("$wa/WhatsApp Images/IMG-2.jpg", 20)
        val fresh = file("$wa/WhatsApp Video/Sent/VID-1.mp4", 3)

        val result = engine.run(listOf(on(RuleId.WHATSAPP_SENT)), 30, now)

        assertEquals(2, result.moved)
        assertFalse(sent.exists() || sentDoc.exists())
        assertTrue(received.exists() && fresh.exists())
    }

    @Test
    fun `Sprachnachrichten trifft nur Audio auch in Wochenordnern`() {
        val voice = file("$wa/WhatsApp Voice Notes/202601/PTT-1.opus", 120)
        val audio = file("$wa/WhatsApp Audio/AUD-1.m4a", 120)
        val image = file("$wa/WhatsApp Images/IMG-1.jpg", 120)

        val result = engine.run(listOf(on(RuleId.WHATSAPP_VOICE)), 30, now)

        assertEquals(2, result.moved)
        assertFalse(voice.exists() || audio.exists())
        assertTrue(image.exists())
    }

    @Test
    fun `Installationsdateien trifft nur APKs`() {
        val apk = file("Download/app-release.APK", 10)
        val pdf = file("Download/rechnung.pdf", 10)
        val young = file("Download/neu.apk", 2)

        val result = engine.run(listOf(on(RuleId.APKS)), 30, now)

        assertEquals(1, result.moved)
        assertFalse(apk.exists())
        assertTrue(pdf.exists() && young.exists())
    }

    @Test
    fun `Telegram trifft alle Dateiarten in seinen Ordnern`() {
        val img = file("Pictures/Telegram/IMG_1.jpg", 90)
        val doc = file("Android/media/org.telegram.messenger/Telegram/Telegram Documents/a.pdf", 90)
        val other = file("Pictures/Urlaub/IMG_2.jpg", 900)

        val result = engine.run(listOf(on(RuleId.TELEGRAM)), 30, now)

        assertEquals(2, result.moved)
        assertFalse(img.exists() || doc.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `Schutzliste hält Ordner mit Unterordnern und einzelne Dateien heraus`() {
        val safeDir = file("Download/Steuer/2025/bescheid.pdf", 400)
        val safeFile = file("Download/vertrag.pdf", 400)
        val normal = file("Download/alt.zip", 400)
        val protection = Protection(
            folders = setOf(File(root, "Download/Steuer").absolutePath),
            files = setOf(safeFile.absolutePath),
        )

        assertEquals(listOf(normal.absolutePath), engine.preview(on(RuleId.DOWNLOADS), now, protection).map { it.file.absolutePath })
        val result = engine.run(listOf(on(RuleId.DOWNLOADS)), RunOptions(protection = protection), now)

        assertEquals(1, result.moved)
        assertTrue(safeDir.exists() && safeFile.exists())
        assertFalse(normal.exists())
    }

    @Test
    fun `ein geschützter Ablageort schützt alles darunter`() {
        val f = file("Download/alt.zip", 400)
        val protection = Protection(folders = setOf(File(root, "Download").absolutePath))
        assertEquals(0, engine.run(listOf(on(RuleId.DOWNLOADS)), RunOptions(protection = protection), now).moved)
        assertTrue(f.exists())
    }

    @Test
    fun `Notbremse hält eine Regel an bis sie bestätigt ist`() {
        val files = (1..4).map { file("Download/alt-$it.bin", 400, bytes = 5) }
        val options = RunOptions(brake = Brake(maxFiles = 3))

        val held = engine.run(listOf(on(RuleId.DOWNLOADS)), options, now)

        assertEquals(0, held.moved)
        assertEquals(listOf(RuleId.DOWNLOADS), held.held.map { it.id })
        assertEquals(4, held.held.single().heldCount)
        assertEquals(20L, held.held.single().heldBytes)
        assertTrue(files.all { it.exists() })

        val confirmed = engine.run(listOf(on(RuleId.DOWNLOADS)), options.copy(confirmed = setOf(RuleId.DOWNLOADS)), now)

        assertEquals(4, confirmed.moved)
        assertTrue(confirmed.held.isEmpty())
    }

    @Test
    fun `Notbremse greift auch bei zu vielen Bytes und lässt kleine Mengen durch`() {
        file("Download/a.bin", 400, bytes = 600)
        file("Download/b.bin", 400, bytes = 600)
        assertTrue(engine.run(listOf(on(RuleId.DOWNLOADS)), RunOptions(brake = Brake(maxFiles = 10, maxBytes = 1000)), now).held.isNotEmpty())
        assertEquals(2, engine.run(listOf(on(RuleId.DOWNLOADS)), RunOptions(brake = Brake(maxFiles = 10, maxBytes = 1200)), now).moved)
    }

    @Test
    fun `leere Ordner verschwinden von unten nach oben und Ablageorte bleiben`() {
        File(root, "Download/leer/tiefer/noch-tiefer").mkdirs()
        File(root, "Pictures/Screenshots").mkdirs()
        File(root, "DCIM/Camera").mkdirs()
        File(root, "Documents").mkdirs()
        File(root, "Movies/.versteckt").mkdirs()
        val used = file("Download/belegt/datei.txt", 1)
        val nomedia = file("Pictures/Album/.nomedia", 1)
        val guarded = File(root, "Download/geschuetzt").apply { mkdirs() }
        val protection = Protection(folders = setOf(guarded.absolutePath))

        val result = engine.run(emptyList(), RunOptions(removeEmptyDirs = true, protection = protection), now)

        assertEquals(3, result.emptyDirs)
        assertFalse(File(root, "Download/leer").exists())
        assertTrue(File(root, "Download").isDirectory && File(root, "Documents").isDirectory)
        assertTrue(File(root, "Pictures/Screenshots").isDirectory && File(root, "DCIM/Camera").isDirectory)
        assertTrue(File(root, "Movies/.versteckt").isDirectory)
        assertTrue(used.exists() && nomedia.exists() && guarded.isDirectory)
    }

    @Test
    fun `leere Ordner bleiben ohne den Schalter liegen`() {
        File(root, "Download/leer").mkdirs()
        assertEquals(0, engine.run(emptyList(), RunOptions(), now).emptyDirs)
        assertTrue(File(root, "Download/leer").isDirectory)
    }

    @Test
    fun `Verlauf merkt sich nur Läufe in denen etwas passiert ist`() {
        val history = History(File(root, "app/verlauf.tsv"), keep = 3)
        history.record(engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now))
        assertTrue(history.entries().isEmpty())

        file("Download/a.bin", 400, bytes = 9)
        history.record(engine.run(listOf(on(RuleId.DOWNLOADS)), 30, now))
        assertEquals(listOf(HistoryEntry(now, "DOWNLOADS", 1, 9)), history.entries())

        history.record(engine.run(emptyList(), 30, now + 40 * day))
        assertEquals(HistoryEntry(now + 40 * day, HistoryEntry.PURGED, 1, 9), history.entries().first())
    }

    @Test
    fun `Verlauf hält angehaltene Regeln fest und behält nur die letzten Zeilen`() {
        val history = History(File(root, "app/verlauf.tsv"), keep = 3)
        repeat(5) { i ->
            file("Download/x-$i.bin", 400)
            file("Download/y-$i.bin", 400)
            history.record(engine.run(listOf(on(RuleId.DOWNLOADS)), RunOptions(brake = Brake(maxFiles = 1)), now + i))
        }
        val entries = history.entries()
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.held })
        assertEquals(now + 4, entries.first().at)
        assertEquals(10, entries.first().count)
        assertEquals(entries.first(), History.parse(History.format(entries.first())))
        assertNull(History.parse("kaputt"))
    }

    @Test
    fun `Duplikate als Regel behalten das Original und unterliegen Schutzliste und Notbremse`() {
        val content = ByteArray(40_000) { (it % 97).toByte() }
        fun copy(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(content); setLastModified(now) }
        val camera = copy("DCIM/Camera/IMG_1.jpg")
        val download = copy("Download/IMG_1.jpg")
        val whatsapp = copy("$wa/WhatsApp Images/IMG-1.jpg")
        val rule = listOf(on(RuleId.DUPLICATES))

        assertEquals(2, engine.preview(on(RuleId.DUPLICATES), now).size)
        assertTrue(engine.run(rule, RunOptions(brake = Brake(maxFiles = 1)), now).held.isNotEmpty())
        val guarded = Protection(files = setOf(download.absolutePath))
        val result = engine.run(rule, RunOptions(protection = guarded), now)

        assertEquals(1, result.moved)
        assertTrue(camera.exists() && download.exists())
        assertFalse(whatsapp.exists())
        assertEquals("DUPLICATES", trash.entries().single().rule)
        assertEquals(0, engine.run(rule, RunOptions(protection = guarded), now).moved)
    }
}
