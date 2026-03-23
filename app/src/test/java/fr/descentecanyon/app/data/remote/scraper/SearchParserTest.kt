package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParserTest {

    private fun loadHtml(name: String): String {
        return javaClass.classLoader!!.getResourceAsStream("html/$name")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `parse furon search results extracts 3 canyons`() {
        val doc = Jsoup.parse(loadHtml("search_furon.html"))
        val results = SearchParser.parse(doc)

        assertEquals(3, results.size)
    }

    @Test
    fun `first result is Furon partie basse with correct id and url`() {
        val doc = Jsoup.parse(loadHtml("search_furon.html"))
        val results = SearchParser.parse(doc)

        val first = results.first()
        assertEquals(27, first.id)
        assertEquals("Le Furon (partie basse)", first.nom)
        assertEquals("/canyoning/canyon/27/Furon-partie-basse.html", first.url)
        assertEquals("Isère", first.departement)
        assertEquals("France", first.pays)
    }

    @Test
    fun `third result is Olette with id 2397`() {
        val doc = Jsoup.parse(loadHtml("search_furon.html"))
        val results = SearchParser.parse(doc)

        val third = results[2]
        assertEquals(2397, third.id)
        assertEquals("Grotte de l' Olette", third.nom)
    }

    @Test
    fun `empty search result returns empty list`() {
        val html = """
            <html><body>
            <tr><td colspan="4">Saississez un nom de canyon dans le champ de recherche.</td></tr>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val results = SearchParser.parse(doc)

        assertTrue(results.isEmpty())
    }
}
