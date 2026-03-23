package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GeoPointParserTest {

    private fun loadHtml(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("html/$name")!!
            .bufferedReader().readText()

    @Test
    fun `parse geopoints returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        assertTrue("Expected geopoints, got empty list", result.isNotEmpty())
    }

    @Test
    fun `geopoints have valid coordinates`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        for (point in result) {
            assertTrue("Latitude should be > 0, got ${point.latitude}", point.latitude > 0)
            assertTrue("Longitude should be != 0, got ${point.longitude}", point.longitude != 0.0)
        }
    }

    @Test
    fun `geopoints have known types`() {
        val validTypes = setOf(
            "PARKING_AVAL", "PARKING_AMONT", "ENTREE", "SORTIE",
            "POINT_REMARQUABLE", "ECHAPPATOIRE", "UNKNOWN",
        )
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        for (point in result) {
            assertTrue(
                "Unknown type: ${point.type}",
                point.type in validTypes,
            )
        }
    }

    @Test
    fun `valouse has parking points`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        val parkings = result.filter { it.type.startsWith("PARKING") }
        assertTrue("Expected at least 1 parking point", parkings.isNotEmpty())
    }

    @Test
    fun `parser handles negative coordinates and spaces around commas`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte_negative_coords.html"))
        val result = GeoPointParser.parse(doc)

        assertEquals(3, result.size)
        assertEquals("ENTREE", result[0].type)
        assertEquals(42.204967, result[0].latitude, 0.000001)
        assertEquals(-0.143080, result[0].longitude, 0.000001)
        assertEquals("SORTIE", result[1].type)
        assertEquals(-0.141814, result[1].longitude, 0.000001)
        assertEquals("PARKING_AVAL", result[2].type)
    }
}
