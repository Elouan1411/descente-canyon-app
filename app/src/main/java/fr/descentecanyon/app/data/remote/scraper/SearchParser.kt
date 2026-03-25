package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import org.jsoup.nodes.Document
import java.util.Locale

/**
 * Parses search results from the canyon database.
 * Search is done via POST to /canyoning with parameter q=query.
 * Results are returned as a list of canyon links.
 */
internal object SearchParser {

    fun parse(doc: Document): List<ScrapedCanyonSummary> {
        val results = mutableListOf<ScrapedCanyonSummary>()

        // Search results are typically links to canyon pages in the main content area
        val canyonLinks = doc.select("a[href~=/canyoning/canyon/\\d+/.*]")

        for (link in canyonLinks) {
            val href = link.attr("href")
            val id = Regex("/canyon/(\\d+)/").find(href)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue

            // Avoid duplicate IDs (same canyon can appear in multiple links)
            if (results.any { it.id == id }) continue

            val nom = link.attr("title").takeIf { it.isNotBlank() } ?: link.text().trim()
            if (nom.isBlank()) continue

            val parentRow = link.closest("tr") ?: link.closest("li") ?: link.parent()
            val locationCell = parentRow?.select("td")?.getOrNull(2)
            val departement = locationCell?.ownText()?.trim()?.ifBlank { null }
            val pays = locationCell
                ?.selectFirst("img[class*=d-]")
                ?.classNames()
                ?.firstNotNullOfOrNull(::countryNameFromFlagClass)
                .orEmpty()

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

    private fun countryNameFromFlagClass(className: String): String? {
        val code = className.substringAfter("d-", "").takeIf { it.length == 2 } ?: return null
        return Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.FRENCH)
            .takeIf { it.isNotBlank() }
            ?.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.FRENCH) else char.toString() }
    }
}
