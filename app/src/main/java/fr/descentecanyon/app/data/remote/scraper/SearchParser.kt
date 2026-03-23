package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import org.jsoup.nodes.Document

/**
 * Parses search results from the canyon AJAX endpoint.
 *
 * The endpoint POST /job/canyonbynom returns HTML table row fragments.
 * We locate canyon links and extract department/country from the
 * closest table row or from the surrounding DOM context.
 */
internal object SearchParser {

    private val CANYON_URL_REGEX = Regex("/canyoning/canyon/(\\d+)/(.+)\\.html")
    private val COUNTRY_NAMES = mapOf(
        "FR" to "France",
        "ES" to "Espagne",
        "IT" to "Italie",
        "CH" to "Suisse",
        "PT" to "Portugal",
        "GR" to "Grece",
        "RE" to "Reunion",
        "MQ" to "Martinique",
        "GP" to "Guadeloupe",
    )

    fun parse(doc: Document): List<ScrapedCanyonSummary> {
        val results = mutableListOf<ScrapedCanyonSummary>()

        val canyonLinks = doc.select("a[href~=/canyoning/canyon/\\d+/]")

        for (link in canyonLinks) {
            val href = link.attr("href")
            val match = CANYON_URL_REGEX.find(href) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue

            if (results.any { it.id == id }) continue

            val nom = link.attr("title").takeIf { it.isNotBlank() }
                ?: link.text().trim()
            if (nom.isBlank()) continue

            // Try to find the enclosing <tr> for structured extraction
            val row = link.closest("tr")

            var departement: String? = null
            var pays = ""

            if (row != null) {
                // Structured: look in the third <td> which contains flag + department
                val locationTd = row.select("td").getOrNull(2)
                departement = locationTd?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                val flagImg = locationTd?.selectFirst("img[class~=d-]")
                pays = flagImg?.className()
                    ?.split(" ")
                    ?.firstOrNull { it.startsWith("d-") && it.length > 2 }
                    ?.removePrefix("d-")
                    ?.uppercase()
                    ?.let { COUNTRY_NAMES[it] ?: it }
                    ?: ""
            } else {
                // Flat DOM: search siblings for flag images
                val flagImg = doc.selectFirst("img[class~=d-]")
                pays = flagImg?.className()
                    ?.split(" ")
                    ?.firstOrNull { it.startsWith("d-") && it.length > 2 }
                    ?.removePrefix("d-")
                    ?.uppercase()
                    ?.let { COUNTRY_NAMES[it] ?: it }
                    ?: ""
            }

            results.add(
                ScrapedCanyonSummary(
                    id = id,
                    nom = nom,
                    pays = pays,
                    departement = departement,
                    url = href,
                )
            )
        }

        return results
    }
}
