package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import org.jsoup.nodes.Document

/**
 * Parses search results from the canyon database.
 * Search is done via POST to /canyoning with parameter q=query.
 * Results are returned as a list of canyon links.
 */
internal object SearchParser {

    fun parse(doc: Document): List<ScrapedCanyonSummary> {
        val results = mutableListOf<ScrapedCanyonSummary>()

        // Search results are typically links to canyon pages in the main content area
        val canyonLinks = doc.select("a[href~=/canyoning/canyon/\\d+/]")

        for (link in canyonLinks) {
            val href = link.attr("href")
            val id = Regex("/canyon/(\\d+)/").find(href)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue

            // Avoid duplicate IDs (same canyon can appear in multiple links)
            if (results.any { it.id == id }) continue

            val nom = link.text().trim()
            if (nom.isBlank()) continue

            // Try to get department/country from surrounding context
            val parentRow = link.closest("tr") ?: link.closest("li") ?: link.parent()
            val departement = parentRow?.select("td")?.getOrNull(1)?.text()?.trim()

            results.add(
                ScrapedCanyonSummary(
                    id = id,
                    nom = nom,
                    departement = departement,
                    url = href,
                )
            )
        }

        return results
    }
}
