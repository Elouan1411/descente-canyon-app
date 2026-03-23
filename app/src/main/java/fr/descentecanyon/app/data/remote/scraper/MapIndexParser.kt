package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import org.json.JSONObject

internal object MapIndexParser {

    fun parse(rawBody: String): List<ScrapedCanyonSummary> {
        val jsonString = rawBody.substringAfter("var data=", missingDelimiterValue = rawBody)
            .removeSuffix(";")
            .trim()

        if (!jsonString.startsWith("{")) return emptyList()

        val root = JSONObject(jsonString)
        val canyons = root.optJSONArray("c") ?: return emptyList()

        return buildList {
            for (i in 0 until canyons.length()) {
                val item = canyons.optJSONObject(i) ?: continue
                val rawId = item.optString("a").trim()
                val id = ("2$rawId").toIntOrNull() ?: continue
                add(
                    ScrapedCanyonSummary(
                        id = id,
                        nom = item.optString("b").trim(),
                        interet = item.optString("e").replace(',', '.').toFloatOrNull(),
                        url = "/canyoning/canyon/$id/${item.optString("h")}.html",
                        latitude = item.optString("c").replace(',', '.').toDoubleOrNull(),
                        longitude = item.optString("d").replace(',', '.').toDoubleOrNull(),
                    )
                )
            }
        }
    }
}
