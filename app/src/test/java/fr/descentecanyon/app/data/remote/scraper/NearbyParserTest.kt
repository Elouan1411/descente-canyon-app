package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyParserTest {

    private fun loadHtml(name: String): String {
        return javaClass.classLoader!!.getResourceAsStream("html/$name")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `parse nearby results extracts 3 canyons with distances`() {
        val doc = Jsoup.parse(loadHtml("nearby_furon.html"))
        val results = NearbyParser.parse(doc)

        assertEquals(3, results.size)
    }

    @Test
    fun `first result is Furon partie haute at 0_4 km`() {
        val doc = Jsoup.parse(loadHtml("nearby_furon.html"))
        val results = NearbyParser.parse(doc)

        val first = results.first()
        assertEquals(26, first.id)
        assertEquals("Furon (partie haute)", first.nom)
        assertEquals(0.4, first.distanceKm!!, 0.01)
        assertEquals("Isère", first.departement)
        assertEquals("France", first.pays)
        assertEquals(null, first.interet)
    }

    @Test
    fun `third result is Infernet at 9_3 km`() {
        val doc = Jsoup.parse(loadHtml("nearby_furon.html"))
        val results = NearbyParser.parse(doc)

        val third = results[2]
        assertEquals(2113, third.id)
        assertEquals("Infernet", third.nom)
        assertEquals(9.3, third.distanceKm!!, 0.01)
        assertEquals(3.2f, third.interet!!, 0.01f)
    }

    @Test
    fun `empty geoloc response returns empty list`() {
        val html = """
            <html><body>
            <table><tbody>
            <tr><td colspan="5">Pas d'information de géolocalisation</td></tr>
            </tbody></table>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val results = NearbyParser.parse(doc)

        assertTrue(results.isEmpty())
    }
}
