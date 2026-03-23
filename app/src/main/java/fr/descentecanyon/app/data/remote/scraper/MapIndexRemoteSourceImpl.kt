package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapIndexRemoteSourceImpl @Inject constructor(
    private val scraper: CanyonScraper,
) : MapIndexRemoteSource {

    private val mutex = Mutex()
    @Volatile private var cached: List<ScrapedCanyonSummary>? = null

    override suspend fun getMapIndex(): Result<List<ScrapedCanyonSummary>> {
        cached?.let { return Result.success(it) }

        return mutex.withLock {
            cached?.let { return@withLock Result.success(it) }
            scraper.scrapeMapIndex().onSuccess { cached = it }
        }
    }
}
