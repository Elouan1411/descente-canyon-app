package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import org.jsoup.nodes.Document

/**
 * Parses a canyon description/topo page: /canyoning/canyon-description/{id}/topo.html
 *
 * Sections are delimited by <h3> headings:
 * Acces, Approche, Descente, Retour, Engagement, Periode/caractere aquatique
 */
internal object DescriptionParser {

    fun parse(doc: Document, canyonId: Int): ScrapedCanyonDetail {
        val sections = mutableMapOf<String, String>()

        // Collect all h3 headings and their following paragraph content
        val h3Elements = doc.select("h3")
        for (h3 in h3Elements) {
            val heading = h3.text().trim().lowercase()
            val content = buildString {
                var sibling = h3.nextElementSibling()
                while (sibling != null && sibling.tagName() != "h3") {
                    if (sibling.tagName() == "p") {
                        if (isNotEmpty()) append("\n\n")
                        append(sibling.wholeText().trim())
                    }
                    sibling = sibling.nextElementSibling()
                }
            }
            if (content.isNotBlank()) {
                sections[heading] = content
            }
        }

        // Split access section into aval/amont
        val accessFull = sections.entries.firstOrNull { it.key.startsWith("acc") }?.value ?: ""
        val (accesAval, accesAmont) = splitAccess(accessFull)

        return ScrapedCanyonDetail(
            id = canyonId,
            accesAval = accesAval,
            accesAmont = accesAmont,
            approche = sections.entries.firstOrNull { it.key.startsWith("approche") }?.value,
            descente = sections.entries.firstOrNull { it.key.startsWith("descente") }?.value,
            retour = sections.entries.firstOrNull { it.key.startsWith("retour") }?.value,
            engagement = sections.entries.firstOrNull { it.key.startsWith("engagement") }?.value,
            periode = sections.entries.firstOrNull {
                it.key.startsWith("p\u00e9riode") || it.key.startsWith("periode")
            }?.value,
        )
    }

    /**
     * The access section contains both "Aval :" and "Amont :" sub-sections in one paragraph.
     */
    private fun splitAccess(accessFull: String): Pair<String?, String?> {
        if (accessFull.isBlank()) return null to null

        val lowerText = accessFull.lowercase()
        val avalIndex = lowerText.indexOf("aval")
        val amontIndex = lowerText.indexOf("amont")

        return when {
            avalIndex >= 0 && amontIndex >= 0 -> {
                val firstIdx = minOf(avalIndex, amontIndex)
                val secondIdx = maxOf(avalIndex, amontIndex)
                val first = accessFull.substring(firstIdx).take(secondIdx - firstIdx).trim()
                    .removePrefix("Aval :").removePrefix("Aval:").removePrefix("aval :").trim()
                val second = accessFull.substring(secondIdx).trim()
                    .removePrefix("Amont :").removePrefix("Amont:").removePrefix("amont :").trim()

                if (avalIndex < amontIndex) first to second
                else second to first
            }
            avalIndex >= 0 -> {
                accessFull.substring(avalIndex)
                    .removePrefix("Aval :").removePrefix("Aval:").trim() to null
            }
            amontIndex >= 0 -> {
                null to accessFull.substring(amontIndex)
                    .removePrefix("Amont :").removePrefix("Amont:").trim()
            }
            else -> accessFull to null
        }
    }
}
