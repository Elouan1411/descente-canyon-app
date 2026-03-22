package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DebitParserTest {

    private fun loadHtml(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("html/$name")!!
            .bufferedReader().readText()

    @Test
    fun `parse canyon debits returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        assertTrue("Expected debits, got empty list", result.isNotEmpty())
    }

    @Test
    fun `canyon debits have correct canyon ID`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        assertTrue(result.all { it.canyonId == 2186 })
    }

    @Test
    fun `canyon debits have valid niveau`() {
        val validLevels = setOf("CRUE", "TRES_GROS", "GROS", "CORRECT", "FILET", "SEC")
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        for (debit in result) {
            assertTrue(
                "Invalid niveau: ${debit.niveauRaw}",
                debit.niveauRaw in validLevels,
            )
        }
    }

    @Test
    fun `canyon debits have non-empty date`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        for (debit in result) {
            assertTrue("Date should not be blank", debit.date.isNotBlank())
        }
    }

    @Test
    fun `parse latest debits returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        assertTrue("Expected latest debits, got empty list", result.isNotEmpty())
    }

    @Test
    fun `latest debits have valid canyon IDs`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        for (debit in result) {
            assertTrue("Canyon ID should be > 0, got ${debit.canyonId}", debit.canyonId > 0)
        }
    }

    @Test
    fun `latest debits have canyon names`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        for (debit in result) {
            assertTrue("Canyon name should not be blank", debit.canyonNom.isNotBlank())
        }
    }
}
