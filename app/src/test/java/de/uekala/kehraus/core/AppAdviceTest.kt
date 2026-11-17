package de.uekala.kehraus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAdviceTest {
    private val now = 1_800_000_000_000L
    private val day = Rules.DAY_MS
    private val mb = Rules.MB

    private fun app(pkg: String, appMb: Long, dataMb: Long, cacheMb: Long = 0, idleDays: Int? = 0, system: Boolean = false) =
        AppUsage(pkg, pkg, appMb * mb, dataMb * mb, cacheMb * mb, if (idleDays == null) 0 else now - idleDays * day, system)

    private val apps = listOf(
        app("spiel", 900, 4000, cacheMb = 300, idleDays = 200),
        app("karten", 100, 2000, cacheMb = 1500, idleDays = 2),
        app("alt-klein", 10, 5, idleDays = 400),
        app("tastatur", 80, 40, idleDays = null),
        app("system", 500, 3000, cacheMb = 50, idleDays = 300, system = true),
        app("kehraus", 20, 40, idleDays = 90),
    )

    @Test
    fun `Größe ist App plus Daten und der Cache steckt in den Daten`() {
        assertEquals(4900 * mb, apps.first().total)
    }

    @Test
    fun `nach Größe mit und ohne System-Apps`() {
        assertEquals(listOf("spiel", "karten", "tastatur", "kehraus", "alt-klein"), AppAdvice.bySize(apps, false).map { it.pkg })
        assertEquals("system", AppAdvice.bySize(apps, true)[1].pkg)
    }

    @Test
    fun `größter Cache zuerst und Kleinkram bleibt weg`() {
        assertEquals(listOf("karten", "spiel", "system"), AppAdvice.byCache(apps).map { it.pkg })
    }

    @Test
    fun `ungenutzt heißt selbst installiert groß genug und nachweislich lange nicht geöffnet`() {
        assertEquals(listOf("spiel"), AppAdvice.unused(apps, now, self = "kehraus").map { it.pkg })
    }

    @Test
    fun `Apps ohne aufgezeichnete Nutzung stehen in einer eigenen Liste`() {
        val more = apps + app("vorinstalliert", 500, 500, idleDays = null, system = true) + app("winzig", 1, 1, idleDays = null)
        assertEquals(listOf("tastatur"), AppAdvice.neverSeen(more, self = "kehraus").map { it.pkg })
    }

    @Test
    fun `Tage seit der letzten Nutzung`() {
        assertEquals(200, apps[0].idleDays(now))
        assertNull(apps[3].idleDays(now))
        assertEquals(0, app("zukunft", 1, 1, idleDays = -3).idleDays(now))
    }
}
