package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import org.jsoup.nodes.Document
import java.util.Locale

/**
 * Parses the nearby canyon AJAX response from POST /job/canyonbygeoloc.
 *
 * Each result row:
 * ```
 * <tr>
 *   <td>0.4 km</td>
 *   <td><img class="smiley" />2.6</td>
 *   <td class="canyonok"><a href="/canyoning/canyon/26/Furon-partie-haute.html" title="...">...</a></td>
 *   <td><img class="d d-fr" />Isère</td>
 *   <td>...</td>
 * </tr>
 * ```
 */
internal object NearbyParser {

    private val CANYON_URL_REGEX = Regex("/canyoning/canyon/(\\d+)/(.+)\\.html")
    private val DISTANCE_REGEX = Regex("""([\d.,]+)\s*km""")
    private val INTEREST_REGEX = Regex("""([\d.,]+)""")

    fun parse(doc: Document): List<ScrapedCanyonSummary> {
        val results = mutableListOf<ScrapedCanyonSummary>()

        // Try table rows first (structured case)
        val rows = doc.select("tr")
        if (rows.isNotEmpty()) {
            for (row in rows) {
                val link = row.selectFirst("a[href~=/canyoning/canyon/\\d+/]") ?: continue
                val summary = extractFromLink(
                    link = link,
                    distanceTd = row.select("td").getOrNull(0),
                    interestTd = row.select("td").getOrNull(1),
                    locationTd = row.select("td").getOrNull(3),
                )
                if (summary != null && results.none { it.id == summary.id }) {
                    results.add(summary)
                }
            }
        }

        // Fallback: flat DOM after JSoup normalization
        if (results.isEmpty()) {
            val links = doc.select("a[href~=/canyoning/canyon/\\d+/]")
            for (link in links) {
                val summary = extractFromLink(link, null, null, null)
                if (summary != null && results.none { it.id == summary.id }) {
                    results.add(summary)
                }
            }
        }

        return results
    }

    private fun extractFromLink(
        link: org.jsoup.nodes.Element,
        distanceTd: org.jsoup.nodes.Element?,
        interestTd: org.jsoup.nodes.Element?,
        locationTd: org.jsoup.nodes.Element?,
    ): ScrapedCanyonSummary? {
        val href = link.attr("href")
        val match = CANYON_URL_REGEX.find(href) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null

        val nom = link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim()
        if (nom.isBlank()) return null

        val distanceText = distanceTd?.text()?.trim() ?: ""
        val distanceKm = DISTANCE_REGEX.find(distanceText)
            ?.groupValues?.get(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()

        val interet = interestTd?.text()?.trim()?.let { text ->
            INTEREST_REGEX.find(text)
                ?.groupValues?.get(1)
                ?.replace(",", ".")
                ?.toFloatOrNull()
        }

        val departement = locationTd?.ownText()?.trim()?.takeIf { it.isNotBlank() }

        val flagImg = locationTd?.selectFirst("img[class~=d-]")
        val pays = flagImg?.className()
            ?.split(" ")
            ?.firstOrNull { it.startsWith("d-") && it.length > 2 }
            ?.removePrefix("d-")
            ?.uppercase()
            ?.toDisplayCountryName()
            ?: ""

        return ScrapedCanyonSummary(
            id = id,
            nom = nom,
            pays = pays,
            departement = departement,
            interet = interet,
            url = href,
            distanceKm = distanceKm,
        )
    }
}

private fun String.toDisplayCountryName(): String {
    val locale = Locale("", this)
    return locale.getDisplayCountry(Locale.getDefault())
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
        ?: this
}
