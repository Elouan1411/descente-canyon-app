package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.network.WebDocumentResponse
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.model.InterestRatingSessionRequiredException
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanyonScraperInterestRatingTest {

    @Test
    fun `getInterestRating parses personal and aggregate ratings`() = runTest {
        val webClient = FakeInterestRatingWebClient(formHtml = interestFormHtml())
        val sessionManager = SessionManager(webClient).apply {
            restoreSession("Antoine", mapOf("phpbb3_hymrt_u" to "9696"))
        }
        val scraper = CanyonScraper(webClient, sessionManager)

        val result = scraper.getInterestRating(26).getOrThrow()

        assertEquals(2.5f, result.personalRating)
        assertEquals(2.6f, result.averageRating)
        assertEquals(2.7f, result.medianRating)
        assertEquals(150, result.voteCount)
    }

    @Test
    fun `submitInterestRating posts dynamic submit field and merged cookies`() = runTest {
        val webClient = FakeInterestRatingWebClient(
            formHtml = interestFormHtml(),
            formCookies = mapOf("dc_session" to "fresh"),
        )
        val sessionManager = SessionManager(webClient).apply {
            restoreSession("Antoine", mapOf("phpbb3_hymrt_u" to "9696"))
        }
        val scraper = CanyonScraper(webClient, sessionManager)

        val result = scraper.submitInterestRating(InterestRatingSubmission(canyonId = 26, rating = 3.7f))

        assertTrue(result.isSuccess)
        assertEquals("3.7", webClient.lastPostData["vote"])
        assertEquals("46140", webClient.lastPostData["id_interet"])
        assertEquals("enregistrer", webClient.lastPostData["valid-230"])
        assertEquals(
            mapOf("phpbb3_hymrt_u" to "9696", "dc_session" to "fresh"),
            webClient.lastPostCookies,
        )
        assertEquals("https://www.descente-canyon.com/canyoning/canyon-interet/26/interet.html", webClient.lastReferer)
        assertEquals("https://www.descente-canyon.com", webClient.lastOrigin)
    }

    @Test
    fun `submitInterestRating fails with session required when form is absent`() = runTest {
        val webClient = FakeInterestRatingWebClient(formHtml = "<html><body>Veuillez vous identifier</body></html>")
        val sessionManager = SessionManager(webClient).apply {
            restoreSession("Antoine", mapOf("phpbb3_hymrt_u" to "9696"))
        }
        val scraper = CanyonScraper(webClient, sessionManager)

        val result = scraper.submitInterestRating(InterestRatingSubmission(canyonId = 26, rating = 2.5f))

        assertTrue(result.exceptionOrNull() is InterestRatingSessionRequiredException)
        assertFalse(sessionManager.isLoggedIn)
        assertFalse(webClient.postCalled)
    }

    private fun interestFormHtml(): String = """
        <html><body>
            <p>Moyenne des notes : <b>2.6</b>/4</p>
            <p>Médiane des notes : <b>2.7</b>/4</p>
            <p><b>Répartition des 150 notes d'intéret</b>.</p>
            <form action="/canyoning/canyon-interet/26/interet.html" method="post">
                <p><b>Antoine</b>, vous avez signalé un intérêt de <b>2.5/4</b> pour ce canyon.</p>
                <input type="hidden" value="46140" name="id_interet" id="id_interet" />
                <input type="text" name="vote" size="4" maxlength="3" />
                <input type="submit" value="enregistrer" name="valid-230" />
            </form>
        </body></html>
    """.trimIndent()
}

private class FakeInterestRatingWebClient(
    private val formHtml: String,
    private val formCookies: Map<String, String> = emptyMap(),
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
            document = Jsoup.parse("<html><body>ok</body></html>", url),
            cookies = emptyMap(),
            finalUrl = url,
        )
    }
}
