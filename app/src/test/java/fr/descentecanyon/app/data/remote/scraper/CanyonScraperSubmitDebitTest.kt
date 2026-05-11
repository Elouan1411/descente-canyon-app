package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.network.WebDocumentResponse
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionSessionExpiredException
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CanyonScraperSubmitDebitTest {

    @Test
    fun `submitDebit fetches form then posts form fields with response cookies`() = runTest {
        val webClient = FakeDebitFormWebClient(
            formHtml = connectedFormHtml(),
            formCookies = mapOf("dc_session" to "fresh"),
            postFinalUrl = "https://www.descente-canyon.com/canyoning/canyon-debit/2186/observations.html",
        )
        val sessionManager = SessionManager(webClient).apply {
            restoreSession("Antoine", mapOf("phpbb3_hymrt_u" to "9696"))
        }
        val scraper = CanyonScraper(webClient, sessionManager)

        val result = scraper.submitDebit(sampleSubmission(observerEmail = null))

        assertTrue(result.isSuccess)
        assertEquals(
            mapOf("phpbb3_hymrt_u" to "9696", "dc_session" to "fresh"),
            webClient.lastPostCookies,
        )
        assertEquals("https://www.descente-canyon.com/canyoning/ajout-debit/2186/formulaire-observation.html", webClient.lastReferer)
        assertEquals("https://www.descente-canyon.com", webClient.lastOrigin)
        assertFalse(webClient.lastPostData.containsKey("groupe"))
        assertFalse(webClient.lastPostData.containsKey("emailgroupe"))
        assertEquals("2026-05-11", webClient.lastPostData["date_mesure"])
        assertEquals("1", webClient.lastPostData["parcouru"])
        assertEquals("4", webClient.lastPostData["debit"])
        assertEquals("3", webClient.lastPostData["eau"])
        assertEquals("3", webClient.lastPostData["air"])
        assertEquals("RAS", webClient.lastPostData["remarque"])
        assertEquals("Perso", webClient.lastPostData["perso"])
    }

    @Test
    fun `submitDebit fails with session expired when saved session opens anonymous form`() = runTest {
        val webClient = FakeDebitFormWebClient(
            formHtml = anonymousFormHtml(),
            formCookies = emptyMap(),
        )
        val sessionManager = SessionManager(webClient).apply {
            restoreSession("antoine", mapOf("dc_session" to "stale"))
        }
        val scraper = CanyonScraper(webClient, sessionManager)

        val result = scraper.submitDebit(sampleSubmission(observerEmail = null))

        assertTrue(result.exceptionOrNull() is DebitSubmissionSessionExpiredException)
        assertFalse(sessionManager.isLoggedIn)
        assertFalse(webClient.postCalled)
    }

    private fun sampleSubmission(observerEmail: String?) = DebitSubmission(
        canyonId = 2186,
        observerName = "Antoine",
        observerEmail = observerEmail,
        observationDate = LocalDate.of(2026, 5, 11),
        observationType = ObservationType.PARCOURU,
        debitLevel = NiveauDebit.CORRECT,
        waterTemperature = WaterTemperature.FROIDE,
        airTemperature = AirTemperature.BON,
        comment = "RAS",
        personalComment = "Perso",
    )

    private fun anonymousFormHtml(): String = """
        <html><body>
            <form id="monformulaire" method="POST">
                <input name="groupe" value="">
                <input name="emailgroupe" value="">
                <input name="date_mesure" value="2026-05-10">
                <input type="hidden" name="parcouru" value="">
                <input type="hidden" name="debit" value="">
                <input type="hidden" name="eau" value="">
                <input type="hidden" name="air" value="">
                <textarea name="remarque"></textarea>
            </form>
        </body></html>
    """.trimIndent()

    private fun connectedFormHtml(): String = """
        <html><body>
            <form id="monformulaire" method="POST">
                <input name="date_mesure" value="2026-05-10">
                <input type="hidden" name="parcouru" value="">
                <input type="hidden" name="debit" value="">
                <input type="hidden" name="eau" value="">
                <input type="hidden" name="air" value="">
                <textarea name="remarque"></textarea>
                <textarea name="perso"></textarea>
            </form>
        </body></html>
    """.trimIndent()
}

private class FakeDebitFormWebClient(
    private val formHtml: String,
    private val formCookies: Map<String, String>,
    private val postFinalUrl: String = "https://www.descente-canyon.com/canyoning/ajout-debit/2186/formulaire-observation.html",
) : DescenteCanyonWebClient() {
    var postCalled = false
    var lastPostData: Map<String, String> = emptyMap()
    var lastPostCookies: Map<String, String> = emptyMap()
    var lastReferer: String? = null
    var lastOrigin: String? = null

    override fun getDocumentResponse(
        url: String,
        cookies: Map<String, String>,
        timeoutMs: Int,
    ): WebDocumentResponse {
        return WebDocumentResponse(
            document = Jsoup.parse(formHtml, url),
            cookies = formCookies,
            finalUrl = url,
        )
    }

    override fun postDocument(
        url: String,
        data: Map<String, String>,
        cookies: Map<String, String>,
        referer: String?,
        origin: String?,
    ): WebDocumentResponse {
        postCalled = true
        lastPostData = data
        lastPostCookies = cookies
        lastReferer = referer
        lastOrigin = origin
        return WebDocumentResponse(
            document = Jsoup.parse("<html><body>ok</body></html>", postFinalUrl),
            cookies = emptyMap(),
            finalUrl = postFinalUrl,
        )
    }
}
