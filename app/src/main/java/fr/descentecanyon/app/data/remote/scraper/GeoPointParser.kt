package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import org.jsoup.nodes.Document

/**
 * Parses geolocated points from the canyon map page.
 * Points are embedded in JavaScript (Google Maps API calls), not in DOM elements.
 */
internal object GeoPointParser {

    private val POINT_REGEX = Regex(
        """LatLng\(([\d.]+),([\d.]+)\),\s*type:\s*'([a-z_]+)'""",
    )

    // Map site type names to our GeoPointType enum names
    private val TYPE_MAP = mapOf(
        "parking" to "PARKING_AVAL",
        "parking_aval" to "PARKING_AVAL",
        "parking_amont" to "PARKING_AMONT",
        "depart" to "ENTREE",
        "arrivee" to "SORTIE",
        "point_externe" to "POINT_REMARQUABLE",
        "point_interne" to "POINT_REMARQUABLE",
    )

    fun parse(doc: Document): List<ScrapedGeoPoint> {
        val scriptElement = doc.selectFirst("script:containsData(initMap)")
            ?: return emptyList()

        val jsCode = scriptElement.data()
        val results = mutableListOf<ScrapedGeoPoint>()

        for (match in POINT_REGEX.findAll(jsCode)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lng = match.groupValues[2].toDoubleOrNull() ?: continue
            val rawType = match.groupValues[3]

            results.add(
                ScrapedGeoPoint(
                    type = TYPE_MAP[rawType] ?: "UNKNOWN",
                    latitude = lat,
                    longitude = lng,
                    label = rawType.replace("_", " "),
                )
            )
        }

        return results
    }
}
