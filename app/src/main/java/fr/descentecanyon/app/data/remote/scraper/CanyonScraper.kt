package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
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
    private val webClient: DescenteCanyonWebClient,
    private val sessionManager: SessionManager,
) {

    companion object {
        const val BASE_URL = "https://www.descente-canyon.com"
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
                    val response = webClient.postDocument(
                        url = "$BASE_URL/canyoning",
                        data = mapOf("q" to query),
                        cookies = sessionManager.getCookies(),
                    )
                    SearchParser.parse(response.document)
                }
            }
        }

    suspend fun scrapeNearbyCanyons(latitude: Double, longitude: Double): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = "$BASE_URL/canyoning/proche.html?lat=$latitude&lng=$longitude"
                    val doc = fetchDocument(url)
                    NearbyParser.parse(doc)
                }
            }
        }

    suspend fun scrapeMapIndex(): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/carte-des-canyons")
                    MapIndexParser.parse(doc.outerHtml())
                }
            }
        }

    suspend fun submitDebit(submission: DebitSubmission): Result<Unit> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = "$BASE_URL/canyoning/ajout-debit/${submission.canyonId}/formulaire-observation.html"
                    val response = webClient.postDocument(
                        url = url,
                        data = mapOf(
                            "groupe" to submission.observerName,
                            "emailgroupe" to submission.observerEmail.orEmpty(),
                            "date_mesure" to submission.observationDate.toString(),
                            "parcouru" to submission.observationType.toFormValue(),
                            "debit" to submission.debitLevel.toFormValue(),
                            "eau" to submission.waterTemperature.toFormValue(),
                            "air" to submission.airTemperature.toFormValue(),
                            "remarque" to submission.comment,
                        ),
                        cookies = sessionManager.getCookies(),
                    )

                    val finalUrl = response.finalUrl
                    val doc = response.document
                    val success = finalUrl.contains("/canyoning/canyon-debit/${submission.canyonId}/observations.html") ||
                        doc.selectFirst("form#monformulaire") == null

                    if (!success) {
                        throw IllegalStateException("Le formulaire de debit a ete refuse par le serveur.")
                    }
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
        return webClient.getDocument(
            url = url,
            cookies = sessionManager.getCookies(),
        )
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
