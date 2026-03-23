package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes canyon data from descente-canyon.com HTML pages.
 * Session cookies from [SessionManager] are attached to all requests when logged in.
 */
@Singleton
class CanyonScraper @Inject constructor(
    private val sessionManager: SessionManager,
) {

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
                    val connection = Jsoup.connect("$BASE_URL/job/canyonbynom")
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .data("nom", query)
                        .ignoreContentType(true)
                    val body = sessionManager.applyTo(connection).execute().body()
                    val doc = Jsoup.parse(
                        "<table><tbody>$body</tbody></table>",
                        BASE_URL,
                    )
                    SearchParser.parse(doc)
                }
            }
        }

    suspend fun submitDebit(submission: DebitSubmission): Result<Unit> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = "$BASE_URL/canyoning/ajout-debit/${submission.canyonId}/formulaire-observation.html"
                    val response = sessionManager.applyTo(
                        Jsoup.connect(url)
                            .userAgent(USER_AGENT)
                            .timeout(TIMEOUT_MS)
                            .method(Connection.Method.POST)
                            .data(
                                mapOf(
                                    "groupe" to submission.observerName,
                                    "emailgroupe" to submission.observerEmail.orEmpty(),
                                    "date_mesure" to submission.observationDate.toString(),
                                    "parcouru" to submission.observationType.toFormValue(),
                                    "debit" to submission.debitLevel.toFormValue(),
                                    "eau" to submission.waterTemperature.toFormValue(),
                                    "air" to submission.airTemperature.toFormValue(),
                                    "remarque" to submission.comment,
                                )
                            )
                    ).execute()

                    val finalUrl = response.url().toString()
                    val doc = response.parse()
                    val success = finalUrl.contains("/canyoning/canyon-debit/${submission.canyonId}/observations.html") ||
                        doc.selectFirst("form#monformulaire") == null

                    if (!success) {
                        throw IllegalStateException("Le formulaire de debit a ete refuse par le serveur.")
                    }
                }
            }
        }

    /**
     * Scrape nearby canyons using the server-side geolocation endpoint.
     * POST /job/canyonbygeoloc with latitude, longitude, interetmin.
     * The server calculates distances and returns sorted HTML table rows.
     */
    suspend fun scrapeNearbyCanyons(
        latitude: Double,
        longitude: Double,
        interetMin: Double = 0.0,
    ): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val connection = Jsoup.connect("$BASE_URL/job/canyonbygeoloc")
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .data("latitude", latitude.toString())
                        .data("longitude", longitude.toString())
                        .data("interetmin", interetMin.toString())
                        .ignoreContentType(true)
                    val doc = sessionManager.applyTo(connection).post()
                    NearbyParser.parse(doc)
                }
            }
        }

    /**
     * Scrape full canyon detail: summary + description + geopoints merged.
     * Acquires only one semaphore permit for the entire composite operation
     * to avoid contention with the 3-permit limit.
     */
    suspend fun scrapeFullCanyonDetail(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val summaryDoc = fetchDocument("$BASE_URL/canyoning/canyon/$canyonId/")
                    val summary = SummaryParser.parse(summaryDoc, canyonId)

                    val descDoc = fetchDocument("$BASE_URL/canyoning/canyon-description/$canyonId/topo.html")
                    val description = DescriptionParser.parse(descDoc, canyonId)

                    val geoPoints = runCatching {
                        val geoDoc = fetchDocument("$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html")
                        GeoPointParser.parse(geoDoc)
                    }.getOrDefault(emptyList())

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
        }

    // --- Internal ---

    private fun fetchDocument(url: String): Document {
        val connection = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
        return sessionManager.applyTo(connection).get()
    }
}

private fun ObservationType.toFormValue(): String = when (this) {
    ObservationType.NON_PARCOURU -> "0"
    ObservationType.PARCOURU -> "1"
}

private fun NiveauDebit.toFormValue(): String = when (this) {
    NiveauDebit.CRUE -> "1"
    NiveauDebit.TRES_GROS -> "2"
    NiveauDebit.GROS -> "3"
    NiveauDebit.CORRECT -> "4"
    NiveauDebit.FILET -> "5"
    NiveauDebit.SEC -> "6"
    NiveauDebit.INCONNU -> ""
}

private fun WaterTemperature.toFormValue(): String = when (this) {
    WaterTemperature.CHAUDE -> "1"
    WaterTemperature.DOUCE -> "2"
    WaterTemperature.FROIDE -> "3"
    WaterTemperature.TRES_FROIDE -> "4"
    WaterTemperature.GLACEE -> "5"
    WaterTemperature.INCONNUE -> ""
}

private fun AirTemperature.toFormValue(): String = when (this) {
    AirTemperature.SUPER_CHAUD -> "1"
    AirTemperature.CHAUD -> "2"
    AirTemperature.BON -> "3"
    AirTemperature.FRISQUET -> "4"
    AirTemperature.FROID -> "5"
    AirTemperature.INCONNUE -> ""
}

// --- Utility extensions ---

internal fun String.extractInt(): Int? =
    replace(Regex("[^\\d]"), "").toIntOrNull()

internal fun String.extractFloat(): Float? =
    replace(",", ".").replace(Regex("[^\\d.]"), "").toFloatOrNull()

internal fun Element.badgeText(pictoClass: String): String? =
    selectFirst("li:has(span.$pictoClass) > span.badge")?.ownText()?.trim()
