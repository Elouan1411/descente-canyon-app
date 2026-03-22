package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.mapper.toDetail
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CanyonRepositoryImpl @Inject constructor(
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val debitDao: DebitDao,
    private val photoDao: PhotoDao,
    private val scraper: CanyonScraper,
) : CanyonRepository {

    override fun searchByName(query: String): Flow<Result<List<CanyonSummary>>> = flow {
        // Emit local results first
        canyonDao.searchByName(query).collect { localResults ->
            if (localResults.isNotEmpty()) {
                emit(Result.success(localResults.map { it.toSummary() }))
            }
        }

        // Then try to fetch from remote
        // TODO: Implement remote search and merge with local results
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        // Try local first
        val localCanyon = canyonDao.getById(canyonId)
        if (localCanyon != null && localCanyon.isOffline) {
            val geoPoints = geoPointDao.getByCanyonId(canyonId)
            val photos = photoDao.getByCanyonId(canyonId)
            val debits = debitDao.getByCanyonId(canyonId)
            // For debits we need a snapshot, not a flow
            return Result.success(localCanyon.toDetail(geoPoints, photos, emptyList()))
        }

        // Fetch from remote
        return scraper.scrapeCanyonDescription(canyonId).map { scraped ->
            val entity = scraped.toEntity()
            canyonDao.insert(entity)

            val geoPointEntities = scraped.geoPoints.map { it.toEntity(canyonId) }
            geoPointDao.deleteByCanyonId(canyonId)
            geoPointDao.insertAll(geoPointEntities)

            entity.toDetail(
                geoPoints = geoPointEntities,
                photos = emptyList(),
                debits = emptyList(),
            )
        }
    }

    override fun getCanyonsNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<Result<List<CanyonSummary>>> = flow {
        // TODO: Implement nearby search using geopoints
        emit(Result.success(emptyList()))
    }

    override suspend fun downloadForOffline(canyonId: Int): Result<Unit> = runCatching {
        // 1. Scrape full canyon data
        val detail = scraper.scrapeCanyonDescription(canyonId).getOrThrow()
        val entity = detail.toEntity().copy(isOffline = true)

        // 2. Save to local DB
        canyonDao.insert(entity)

        val geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) }
        geoPointDao.deleteByCanyonId(canyonId)
        geoPointDao.insertAll(geoPointEntities)

        // 3. TODO: Download map tiles for offline use
        // 4. TODO: Optionally download photos
    }

    override suspend fun removeOfflineData(canyonId: Int): Result<Unit> = runCatching {
        canyonDao.setOffline(canyonId, false)
        // TODO: Remove cached map tiles and photos
    }

    override fun getOfflineCanyons(): Flow<List<CanyonSummary>> {
        return canyonDao.getOfflineCanyons().map { entities ->
            entities.map { it.toSummary() }
        }
    }

    override fun getCanyonsByLocation(locationPath: String): Flow<Result<List<CanyonSummary>>> = flow {
        // TODO: Implement location-based browsing
        emit(Result.success(emptyList()))
    }
}
