package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DescriptionParserTest {

    private fun loadHtml(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("html/$name")!!
            .bufferedReader().readText()

    private fun parseValouse() =
        DescriptionParser.parse(Jsoup.parse(loadHtml("canyon_description.html")), 2186)

    @Test
    fun `parse access aval`() {
        val result = parseValouse()
        assertNotNull("accesAval should not be null", result.accesAval)
        assertTrue(
            "accesAval should mention Burbanche or Hopitaux",
            result.accesAval!!.contains("Burbanche") || result.accesAval!!.contains("H\u00f4pitaux"),
        )
    }

    @Test
    fun `parse access amont`() {
        val result = parseValouse()
        assertNotNull("accesAmont should not be null", result.accesAmont)
        assertTrue(
            "accesAmont should mention Tare or plateau",
            result.accesAmont!!.contains("Tare") || result.accesAmont!!.contains("plateau"),
        )
    }

    @Test
    fun `parse approche`() {
        val result = parseValouse()
        assertNotNull("approche should not be null", result.approche)
        assertTrue(result.approche!!.contains("piste") || result.approche!!.contains("cl\u00f4tures"))
    }

    @Test
    fun `parse descente`() {
        val result = parseValouse()
        assertNotNull("descente should not be null", result.descente)
        assertTrue(result.descente!!.contains("cascades") || result.descente!!.contains("rappel"))
    }

    @Test
    fun `parse retour`() {
        val result = parseValouse()
        assertNotNull("retour should not be null", result.retour)
    }

    @Test
    fun `parse engagement`() {
        val result = parseValouse()
        assertNotNull("engagement should not be null", result.engagement)
        assertTrue(result.engagement!!.contains("chappatoire") || result.engagement!!.lowercase().contains("echapp"))
    }

    @Test
    fun `parse periode`() {
        val result = parseValouse()
        assertNotNull("periode should not be null", result.periode)
        assertTrue(result.periode!!.contains("sec") || result.periode!!.contains("pluie"))
    }
}
