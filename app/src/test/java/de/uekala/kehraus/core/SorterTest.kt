package de.uekala.kehraus.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SorterTest {
    private lateinit var root: File
    private lateinit var journal: File
    private val now = 1_800_000_000_000L
    private val day = Rules.DAY_MS

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kehraus-sortieren").toFile()
        journal = File(root, "app/sortierung.tsv")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String, ageDays: Int, text: String = "x"): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        f.setLastModified(now - ageDays * day)
        return f
    }

    private fun sort() = DownloadSorter.apply(DownloadSorter.plan(root, now), journal)

    @Test
    fun `lose Downloads wandern nach Art in Unterordner`() {
        file("Download/rechnung.pdf", 30)
        file("Download/foto.JPG", 30)
        file("Download/app.apk", 30)
        file("Download/paket.zip", 30)
        val unknown = file("Download/daten.xyz", 30)
        val fresh = file("Download/neu.pdf", 2)
        val nested = file("Download/Projekt/plan.pdf", 30)
        val hidden = file("Download/.verborgen.pdf", 30)

        assertEquals(4, sort().size)

        assertTrue(File(root, "Download/Dokumente/rechnung.pdf").isFile)
        assertTrue(File(root, "Download/Bilder/foto.JPG").isFile)
        assertTrue(File(root, "Download/Installationsdateien/app.apk").isFile)
        assertTrue(File(root, "Download/Archive/paket.zip").isFile)
        assertTrue(unknown.exists() && fresh.exists() && nested.exists() && hidden.exists())
        assertEquals(0, sort().size)
    }

    @Test
    fun `gleicher Name im Zielordner wird nicht überschrieben`() {
        file("Download/Dokumente/a.pdf", 100, "alt")
        file("Download/a.pdf", 30, "neu")

        val done = sort().single()

        assertEquals("a (2).pdf", File(done.to).name)
        assertEquals("alt", File(root, "Download/Dokumente/a.pdf").readText())
        assertEquals("neu", File(done.to).readText())
    }

    @Test
    fun `Schutzliste gilt auch beim Sortieren`() {
        val guarded = file("Download/vertrag.pdf", 30)
        file("Download/b.pdf", 30)
        val plan = DownloadSorter.plan(root, now, protection = Protection(files = setOf(guarded.absolutePath)))
        assertEquals(listOf("b.pdf"), plan.map { File(it.from).name })
        assertTrue(DownloadSorter.plan(root, now, protection = Protection(folders = setOf(File(root, "Download").absolutePath))).isEmpty())
    }

    @Test
    fun `Zurücknehmen legt alles an den alten Platz und räumt leere Ordner weg`() {
        val a = file("Download/a.pdf", 30)
        val b = file("Download/b.jpg", 30)
        val c = file("Download/c.zip", 30)
        sort()
        assertEquals(3, DownloadSorter.pending(journal))
        File(root, "Download/Archive/c.zip").delete()
        file("Download/Dokumente/bleibt.pdf", 1)

        val undone = DownloadSorter.undo(journal)

        assertEquals(2, undone.size)
        assertTrue(a.isFile && b.isFile)
        assertFalse(c.exists())
        assertFalse(File(root, "Download/Bilder").exists())
        assertTrue(File(root, "Download/Dokumente/bleibt.pdf").isFile)
        assertEquals(0, DownloadSorter.pending(journal))
    }

    @Test
    fun `der Lauf sortiert nur mit Schalter und meldet die Zahl`() {
        val engine = Engine(root, Trash(File(root, "Kehraus/Papierkorb")))
        file("Download/a.pdf", 30)
        assertEquals(0, engine.run(emptyList(), RunOptions(), now).sorted)

        val result = engine.run(emptyList(), RunOptions(sortJournal = journal), now)

        assertEquals(1, result.sorted)
        assertTrue(result.changedPaths.any { it.endsWith("Download/Dokumente/a.pdf") })
        val history = History(File(root, "app/verlauf.tsv"))
        history.record(result)
        assertEquals(HistoryEntry(now, HistoryEntry.SORTED, 1, 0), history.entries().single())
    }

    @Test
    fun `Installationsdateien werden überall gefunden außer in App-Ordnern`() {
        val engine = Engine(root, Trash(File(root, "Kehraus/Papierkorb")))
        val a = file("Download/a.apk", 30)
        val b = file("Backups/Apps/b.apk", 30)
        val c = file("lose.apk", 30)
        val inApp = file("Android/media/com.beispiel/c.apk", 30)
        val hidden = file(".cache/d.apk", 30)
        val other = file("Backups/Apps/notiz.txt", 30)

        val result = engine.run(listOf(RuleConfig(RuleId.APKS, enabled = true)), RunOptions(), now)

        assertEquals(3, result.moved)
        assertFalse(a.exists() || b.exists() || c.exists())
        assertTrue(inApp.exists() && hidden.exists() && other.exists())
    }
}
