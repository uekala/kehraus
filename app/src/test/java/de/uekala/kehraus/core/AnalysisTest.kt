package de.uekala.kehraus.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnalysisTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kehraus-analyse").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, content: ByteArray, modified: Long = 1_000_000L): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(content)
        f.setLastModified(modified)
        return f
    }

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) % 251).toByte() }

    @Test
    fun `Analyse summiert Ordner von unten nach oben`() {
        file("DCIM/Camera/a.jpg", bytes(100))
        file("DCIM/Camera/b.mp4", bytes(300))
        file("DCIM/.thumbnails/t.jpg", bytes(7))
        file("Download/x.pdf", bytes(50))
        file("lose.bin", bytes(5))

        val r = Analysis.scan(root)

        assertEquals(Usage(462, 5), r.total)
        assertEquals(Usage(407, 3), r.folders[File(root, "DCIM").absolutePath])
        assertEquals(Usage(400, 2), r.folders[File(root, "DCIM/Camera").absolutePath])
        assertEquals(listOf("DCIM", "Download"), r.subfolders(r.root).map { File(it.first).name })
        assertEquals(listOf("b.mp4", "a.jpg"), r.filesIn(File(root, "DCIM/Camera").absolutePath).map { it.name })
        assertEquals(listOf("lose.bin"), r.filesIn(r.root).map { it.name })
        assertEquals("b.mp4", r.largest.first().name)
    }

    @Test
    fun `Analyse ordnet nach Dateiart und lässt Papierkorb und gesperrte Ordner aus`() {
        file("a.jpg", bytes(10))
        file("b.pdf", bytes(20))
        file("c.zip", bytes(30))
        file("d.apk", bytes(40))
        file("e.xyz", bytes(50))
        file("Kehraus/Papierkorb/files/alt.jpg", bytes(999))
        file("Android/data/com.example/cache.bin", bytes(999))
        file("Android/media/com.whatsapp/WhatsApp/Media/x.opus", bytes(60))

        val r = Analysis.scan(root, exclude = File(root, "Kehraus/Papierkorb"))

        assertEquals(Usage(210, 6), r.total)
        assertEquals(Usage(10, 1), r.byCategory[Category.IMAGE])
        assertEquals(Usage(20, 1), r.byCategory[Category.DOCUMENT])
        assertEquals(Usage(30, 1), r.byCategory[Category.ARCHIVE])
        assertEquals(Usage(40, 1), r.byCategory[Category.APK])
        assertEquals(Usage(50, 1), r.byCategory[Category.OTHER])
        assertEquals(Usage(60, 1), r.byCategory[Category.AUDIO])
        assertFalse(r.folders.containsKey(File(root, "Android/data").absolutePath))
    }

    private fun dups(protection: Protection = Protection(), minSize: Long = 1) =
        Duplicates.find(Analysis.scan(root).files, root.absolutePath, protection, minSize)

    @Test
    fun `Duplikate behalten das Kamerabild und melden die Kopien`() {
        val content = bytes(200_000)
        val camera = file("DCIM/Camera/IMG_1.jpg", content)
        val whatsapp = file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg", content)
        val download = file("Download/IMG_1 (1).jpg", content)
        file("DCIM/Camera/IMG_2.jpg", bytes(200_000, seed = 3))

        val group = dups().single()

        assertEquals(camera.absolutePath, group.keep.path)
        assertEquals(setOf(whatsapp.absolutePath, download.absolutePath), group.copies.map { it.path }.toSet())
        assertEquals(400_000L, group.wasted)
    }

    @Test
    fun `gleiche Größe und gleicher Anfang reichen nicht`() {
        val a = bytes(150_000)
        val b = a.copyOf().also { it[149_999] = 1 }
        file("Download/a.bin", a)
        file("Download/b.bin", b)
        assertTrue(dups().isEmpty())
    }

    @Test
    fun `bei gleichem Rang bleibt die ältere Datei`() {
        val content = bytes(5_000)
        file("Download/neu.pdf", content, modified = 9_000_000L)
        val old = file("Download/alt.pdf", content, modified = 1_000_000L)
        assertEquals(old.absolutePath, dups().single().keep.path)
    }

    @Test
    fun `Geschütztes ist nie die Kopie und Verstecktes bleibt außen vor`() {
        val content = bytes(5_000)
        file("DCIM/Camera/a.jpg", content)
        val guarded = file("Download/a.jpg", content)
        file("Pictures/.versteckt/a.jpg", content)
        file("Android/media/com.andere.app/a.jpg", content)

        assertEquals(listOf(guarded.absolutePath), dups().single().copies.map { it.path })
        assertTrue(dups(Protection(files = setOf(guarded.absolutePath))).isEmpty())
    }

    @Test
    fun `kleine Dateien werden übersprungen`() {
        file("Download/a.txt", bytes(100))
        file("Download/b.txt", bytes(100))
        assertTrue(Duplicates.find(Analysis.scan(root).files, root.absolutePath).isEmpty())
        assertEquals(1, dups(minSize = 1).size)
    }

    @Test
    fun `Rangfolge der Ablageorte`() {
        val order = listOf(
            "DCIM/Camera/a.jpg", "DCIM/Urlaub/a.jpg", "Pictures/Album/a.jpg", "Documents/a.jpg", "Sonstwo/a.jpg",
            "Download/a.jpg", "Pictures/Screenshots/a.jpg", "Pictures/Telegram/a.jpg",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/a.jpg",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/a.jpg",
        )
        assertEquals(order, order.shuffled(java.util.Random(1)).sortedBy { Duplicates.rank(it) })
    }

    @Test
    fun `Reste deinstallierter Apps werden nur bei glaubwürdiger App-Liste gemeldet`() {
        file("Android/media/com.weg.app/Bilder/a.jpg", bytes(100))
        file("Android/media/com.weg.app/b.bin", bytes(50))
        file("Android/media/com.da.app/c.jpg", bytes(70))
        file("Android/media/keinpaket/d.jpg", bytes(70))
        File(root, "Android/media/com.leer.app").mkdirs()
        val report = Analysis.scan(root)
        val installed = (1..30).map { "com.beispiel.app$it" }.toSet() + "com.da.app"

        val orphan = Orphans.find(report, installed).single()

        assertEquals("com.weg.app", orphan.pkg)
        assertEquals(Usage(150, 2), orphan.usage)
        assertEquals(setOf("a.jpg", "b.bin"), Orphans.filesOf(report, orphan).map { it.name }.toSet())
        assertTrue(Orphans.find(report, setOf("com.da.app")).isEmpty())
    }
}
