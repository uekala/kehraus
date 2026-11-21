package de.uekala.kehraus.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset

class OrganizeTest {
    private lateinit var root: File
    private val utc = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toInstant(utc).toEpochMilli()
    private val now = at(2026, 11, 21)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kehraus-ordnen").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, modified: Long, text: String = "x"): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        f.setLastModified(modified)
        return f
    }

    // ---------- Screenshots nach Monat ----------

    @Test
    fun `Screenshots wandern in Monatsordner und der laufende Monat bleibt`() {
        file("Pictures/Screenshots/a.png", at(2026, 10, 3))
        file("Pictures/Screenshots/b.png", at(2025, 12, 31))
        val current = file("Pictures/Screenshots/c.png", at(2026, 11, 2))
        val nested = file("Pictures/Screenshots/2026-01/d.png", at(2026, 1, 5))
        file("DCIM/Screenshots/e.mp4", at(2026, 9, 9))
        file("Pictures/Screenshots/notiz.txt", at(2026, 1, 1))

        val plan = ScreenshotSorter.plan(root, now, zone = utc)

        assertEquals(
            setOf("Pictures/Screenshots/2026-10/a.png", "Pictures/Screenshots/2025-12/b.png", "DCIM/Screenshots/2026-09/e.mp4"),
            plan.map { it.to.removePrefix(root.absolutePath + "/") }.toSet(),
        )
        val journal = File(root, "app/screens.tsv")
        assertEquals(3, Journal.apply(plan, journal).size)
        assertTrue(current.exists() && nested.exists())
        assertTrue(ScreenshotSorter.plan(root, now, zone = utc).isEmpty())

        assertEquals(3, Journal.undo(journal).size)
        assertTrue(File(root, "Pictures/Screenshots/a.png").isFile)
        assertFalse(File(root, "Pictures/Screenshots/2026-10").exists())
    }

    @Test
    fun `der Lauf teilt Screenshots nur mit Schalter auf`() {
        val engine = Engine(root, Trash(File(root, "Kehraus/Papierkorb")))
        file("Pictures/Screenshots/a.png", at(2020, 1, 1))
        assertEquals(0, engine.run(emptyList(), RunOptions(), now).sorted)
        assertEquals(1, engine.run(emptyList(), RunOptions(screenshotJournal = File(root, "app/s.tsv")), now).sorted)
    }

    // ---------- Belege erkennen ----------

    @Test
    fun `Kassenbon und Brief sind Dokumente, Urlaubsfoto nicht`() {
        val bon = """
            REWE Markt GmbH
            Milch 1,19
            Brot 2,49
            Kaese 3,79
            SUMME EUR 7,47
            Kartenzahlung
            MwSt 7% 0,49
        """.trimIndent()
        val brief = """
            Sehr geehrter Herr Kala,
            vielen Dank für Ihre Anfrage vom 12. November. Anbei erhalten Sie die gewünschten Unterlagen
            zu Ihrem Vertrag. Bitte prüfen Sie die Angaben und senden Sie uns das unterschriebene Exemplar zurück.
            Mit freundlichen Grüßen
        """.trimIndent()
        assertTrue(DocClassifier.isDocument(bon))
        assertTrue(DocClassifier.isDocument(brief))
        assertFalse(DocClassifier.isDocument("STOP"))
        assertFalse(DocClassifier.isDocument("Strand Bar Café Sonnenschein"))
        assertFalse(DocClassifier.isDocument(""))
    }

    @Test
    fun `Fotos werden einmal geprüft und Dokumente einsortiert`() {
        val old = at(2026, 10, 1)
        val bon = file("DCIM/Camera/IMG_1.jpg", old)
        val urlaub = file("DCIM/Camera/IMG_2.jpg", old)
        val wa = file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-3.jpg", old)
        val sent = file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-4.jpg", old)
        val fresh = file("DCIM/Camera/IMG_5.jpg", now - 3_600_000)
        file("DCIM/Camera/VID_6.mp4", old)
        val cacheFile = DocCache(File(root, "app/belege.tsv"))

        val todo = Documents.candidates(root, now, cacheFile.load())
        assertEquals(setOf(bon, urlaub, wa).map { it.absolutePath }.toSet(), todo.map { it.absolutePath }.toSet())

        cacheFile.add(listOf(
            DocVerdict(bon.absolutePath, bon.lastModified(), true),
            DocVerdict(urlaub.absolutePath, urlaub.lastModified(), false),
            DocVerdict(wa.absolutePath, wa.lastModified(), true),
        ))
        val cache = cacheFile.load()
        assertTrue(Documents.candidates(root, now, cache).isEmpty())

        val guarded = Protection(files = setOf(wa.absolutePath))
        val plan = Documents.plan(root, now, cache, guarded)
        assertEquals(listOf(bon.absolutePath), plan.map { it.from })

        val journal = File(root, "app/belege-journal.tsv")
        Journal.apply(Documents.plan(root, now, cache), journal)
        assertTrue(File(root, "Pictures/Dokumente/IMG_1.jpg").isFile && File(root, "Pictures/Dokumente/IMG-3.jpg").isFile)
        assertTrue(urlaub.exists() && sent.exists() && fresh.exists())
        assertEquals(2, Journal.pending(journal))

        // Geändertes Foto wird erneut geprüft
        urlaub.setLastModified(old + 60_000)
        assertEquals(listOf(urlaub.absolutePath), Documents.candidates(root, now, cacheFile.load()).map { it.absolutePath })
    }

    // ---------- WhatsApp-Bilder behalten ----------

    @Test
    fun `Behalten legt das Bild ins eigene Album, ohne etwas zu überschreiben`() {
        val base = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"
        val a = file("$base/IMG-1.jpg", at(2026, 1, 1), "erstes")
        val b = file("$base/Sent/IMG-1.jpg", at(2026, 1, 1), "zweites")

        val kept1 = Keeper.keep(root, a)
        val kept2 = Keeper.keep(root, b)

        assertEquals("Pictures/WhatsApp behalten/IMG-1.jpg", kept1!!.absolutePath.removePrefix(root.absolutePath + "/"))
        assertEquals("IMG-1 (2).jpg", kept2!!.name)
        assertEquals("zweites", kept2.readText())
        assertFalse(a.exists() || b.exists())
        assertNull(Keeper.keep(root, a))

        val engine = Engine(root, Trash(File(root, "Kehraus/Papierkorb")))
        assertTrue(engine.preview(RuleConfig(RuleId.WHATSAPP, enabled = true, ageDays = 1), now).isEmpty())
    }
}
