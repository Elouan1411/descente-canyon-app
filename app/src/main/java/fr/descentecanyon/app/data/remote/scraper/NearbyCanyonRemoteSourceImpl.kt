package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyCanyonRemoteSourceImpl @Inject constructor(
    private val scraper: CanyonScraper,
) : NearbyCanyonRemoteSource {
    override suspend fun getNearbyCanyons(
        latitude: Double,
        longitude: Double,
    ): Result<List<ScrapedCanyonSummary>> {
        return scraper.scrapeNearbyCanyons(latitude, longitude)
    }
}
