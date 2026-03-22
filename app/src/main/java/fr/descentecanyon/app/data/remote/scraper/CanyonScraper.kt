package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes canyon data from descente-canyon.com HTML pages.
 *
 * URL patterns:
 * - Canyon summary: /canyoning/canyon/{id}/{slug}.html
 * - Canyon description: /canyoning/canyon-description/{id}/topo.html
 * - Canyon map: /canyoning/canyon-carte/{id}/carte.html
 * - Canyon photos: /canyoning/canyon-photo/{id}/photographie.html
 * - Canyon debits: /canyoning/canyon-debit/{id}/observations.html
 * - Search: /canyoning (with query params)
 * - Latest debits: /canyoning/derniers-debits
 */
@Singleton
class CanyonScraper @Inject constructor() {

    companion object {
        const val BASE_URL = "https://www.descente-canyon.com"
        private const val USER_AGENT =
            "DescenteCanyonApp/0.1 (Android; fr.descentecanyon.app)"
        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Fetch and parse a canyon summary page.
     */
    suspend fun scrapeCanyonSummary(canyonId: Int): Result<ScrapedCanyonDetail> {
        return runCatching {
            val doc = fetchDocument("$BASE_URL/canyoning/canyon/$canyonId/")
            parseCanyonSummaryPage(doc, canyonId)
        }
    }

    /**
     * Fetch and parse a canyon full description (topo).
     */
    suspend fun scrapeCanyonDescription(canyonId: Int): Result<ScrapedCanyonDetail> {
        return runCatching {
            val doc = fetchDocument("$BASE_URL/canyoning/canyon-description/$canyonId/topo.html")
            parseCanyonDescriptionPage(doc, canyonId)
        }
    }

    /**
     * Fetch and parse the latest debits page.
     */
    suspend fun scrapeLatestDebits(): Result<List<ScrapedDebit>> {
        return runCatching {
            val doc = fetchDocument("$BASE_URL/canyoning/derniers-debits")
            parseDebitsPage(doc)
        }
    }

    /**
     * Search canyons by name via the site's search.
     */
    suspend fun searchCanyons(query: String): Result<List<ScrapedCanyonSummary>> {
        return runCatching {
            val doc = Jsoup.connect("$BASE_URL/canyoning")
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .data("q", query)
                .post()
            parseSearchResults(doc)
        }
    }

    // --- Private parsing methods (to be implemented) ---

    private fun fetchDocument(url: String): Document {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
    }

    private fun parseCanyonSummaryPage(doc: Document, canyonId: Int): ScrapedCanyonDetail {
        // TODO: Implement HTML parsing for canyon summary page
        // Parse: name, location, cotation, altitudes, times, interest rating
        throw NotImplementedError("Canyon summary parsing not yet implemented")
    }

    private fun parseCanyonDescriptionPage(doc: Document, canyonId: Int): ScrapedCanyonDetail {
        // TODO: Implement HTML parsing for canyon description/topo page
        // Parse: access, approach, descent, return, engagement, period
        throw NotImplementedError("Canyon description parsing not yet implemented")
    }

    private fun parseDebitsPage(doc: Document): List<ScrapedDebit> {
        // TODO: Implement HTML parsing for debits page
        throw NotImplementedError("Debits parsing not yet implemented")
    }

    private fun parseSearchResults(doc: Document): List<ScrapedCanyonSummary> {
        // TODO: Implement HTML parsing for search results
        throw NotImplementedError("Search results parsing not yet implemented")
    }
}
