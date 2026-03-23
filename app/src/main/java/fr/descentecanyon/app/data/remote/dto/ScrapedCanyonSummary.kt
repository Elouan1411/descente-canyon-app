package fr.descentecanyon.app.data.remote.dto

/**
 * Lightweight scraped canyon data from search results or list pages.
 */
data class ScrapedCanyonSummary(
    val id: Int,
    val nom: String,
    val pays: String = "",
    val departement: String? = null,
    val cotation: String = "",
    val url: String = "",
    val distanceKm: Double? = null,
)
