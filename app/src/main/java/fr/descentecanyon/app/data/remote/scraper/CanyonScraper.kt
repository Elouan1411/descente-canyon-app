package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes canyon data from descente-canyon.com HTML pages.
 */
@Singleton
class CanyonScraper @Inject constructor() {

    companion object {
        const val BASE_URL = "https://www.descente-canyon.com"
        private val USER_AGENT = "DescenteCanyonApp/${BuildConfig.VERSION_NAME} (Android)"
        private const val TIMEOUT_MS = 15_000
    }

    // Rate limit: max 3 concurrent requests to avoid overloading the site
    private val semaphore = Semaphore(3)

    // --- Public API ---

    suspend fun scrapeCanyonSummary(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon/$canyonId/")
                    SummaryParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonDescription(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-description/$canyonId/topo.html")
                    DescriptionParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonGeoPoints(canyonId: Int): Result<List<ScrapedGeoPoint>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html")
                    GeoPointParser.parse(doc)
                }
            }
        }

    suspend fun scrapeCanyonPhotos(canyonId: Int): Result<List<ScrapedPhoto>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-photo/$canyonId/photographie.html")
                    PhotoParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonDebits(canyonId: Int): Result<List<ScrapedDebit>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-debit/$canyonId/observations.html")
                    DebitParser.parseCanyonDebits(doc, canyonId)
                }
            }
        }

    suspend fun scrapeLatestDebits(): Result<List<ScrapedDebit>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/derniers-debits")
                    DebitParser.parseLatestDebits(doc)
                }
            }
        }

    suspend fun searchCanyons(query: String): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = Jsoup.connect("$BASE_URL/canyoning")
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .data("q", query)
                        .post()
                    SearchParser.parse(doc)
                }
            }
        }

    /**
     * Scrape full canyon detail: summary + description + geopoints merged.
     */
    suspend fun scrapeFullCanyonDetail(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val summary = scrapeCanyonSummary(canyonId).getOrThrow()
                val description = scrapeCanyonDescription(canyonId).getOrThrow()
                val geoPoints = scrapeCanyonGeoPoints(canyonId).getOrDefault(emptyList())

                summary.copy(
                    accesAval = description.accesAval,
                    accesAmont = description.accesAmont,
                    approche = description.approche,
                    descente = description.descente,
                    retour = description.retour,
                    engagement = description.engagement,
                    periode = description.periode,
                    geoPoints = geoPoints,
                )
            }
        }

    // --- Internal ---

    private fun fetchDocument(url: String): Document {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()
    }
}

// --- Utility extensions ---

internal fun String.extractInt(): Int? =
    replace(Regex("[^\\d]"), "").toIntOrNull()

internal fun String.extractFloat(): Float? =
    replace(",", ".").replace(Regex("[^\\d.]"), "").toFloatOrNull()

internal fun Element.badgeText(pictoClass: String): String? =
    selectFirst("li:has(span.$pictoClass) > span.badge")?.ownText()?.trim()
