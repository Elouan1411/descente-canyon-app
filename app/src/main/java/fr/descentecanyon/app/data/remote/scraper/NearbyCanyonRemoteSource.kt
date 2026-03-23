package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary

interface NearbyCanyonRemoteSource {
    suspend fun getNearbyCanyons(
        latitude: Double,
        longitude: Double,
    ): Result<List<ScrapedCanyonSummary>>
}
