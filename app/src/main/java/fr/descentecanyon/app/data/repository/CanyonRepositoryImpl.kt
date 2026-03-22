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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

    /**
     * B-1 fix: Use Room's reactive Flow directly instead of collecting inside a flow{} builder
     * (which would block forever since Room Flows never complete).
     * Remote results are fetched on subscription via onStart and inserted into DB,
     * which automatically triggers a new emission from the Room Flow.
     */
    override fun searchByName(query: String): Flow<Result<List<CanyonSummary>>> {
        return canyonDao.searchByName(query)
            .map<List<fr.descentecanyon.app.data.local.entity.CanyonEntity>, Result<List<CanyonSummary>>> { entities ->
                Result.success(entities.map { it.toSummary() })
            }
            .onStart {
                // Fire-and-forget remote fetch; inserted rows trigger a new Flow emission
                try {
                    val remoteResults = scraper.searchCanyons(query).getOrNull()
                    if (!remoteResults.isNullOrEmpty()) {
                        val entities = remoteResults.map { it.toEntity() }
                        insertAllPreservingFlags(entities)
                    }
                } catch (_: Exception) {
                    // Remote failure is non-fatal; local results still flow
                }
            }
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        // Try local first
        val localCanyon = canyonDao.getById(canyonId)
        if (localCanyon != null && localCanyon.isOffline) {
            val geoPoints = geoPointDao.getByCanyonId(canyonId)
            val photos = photoDao.getByCanyonId(canyonId)
            return Result.success(localCanyon.toDetail(geoPoints, photos, emptyList()))
        }

        // Fetch from remote
        return scraper.scrapeCanyonDescription(canyonId).map { scraped ->
            val entity = scraped.toEntity()
            insertPreservingFlags(entity)

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
    ): Flow<Result<List<CanyonSummary>>> {
        // TODO: Implement nearby search using geopoints
        return canyonDao.searchByName("").map { entities ->
            Result.success(entities.map { it.toSummary() })
        }
    }

    override suspend fun downloadForOffline(canyonId: Int): Result<Unit> = runCatching {
        // 1. Scrape full canyon data
        val detail = scraper.scrapeCanyonDescription(canyonId).getOrThrow()
        val entity = detail.toEntity().copy(isOffline = true)

        // 2. Save to local DB, preserving isFavorite
        insertPreservingFlags(entity)

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

    override fun getCanyonsByLocation(locationPath: String): Flow<Result<List<CanyonSummary>>> {
        // TODO: Implement location-based browsing
        return canyonDao.searchByName("").map { entities ->
            Result.success(entities.map { it.toSummary() })
        }
    }

    // --- B-4/B-5 fix: Preserve isFavorite and isOffline flags on insert ---

    /**
     * Insert a single entity while preserving existing user flags (isFavorite, isOffline).
     */
    private suspend fun insertPreservingFlags(entity: fr.descentecanyon.app.data.local.entity.CanyonEntity) {
        val existing = canyonDao.getById(entity.id)
        val merged = if (existing != null) {
            entity.copy(
                isFavorite = existing.isFavorite,
                isOffline = entity.isOffline || existing.isOffline,
            )
        } else {
            entity
        }
        canyonDao.insert(merged)
    }

    /**
     * Insert a list of entities while preserving existing user flags.
     */
    private suspend fun insertAllPreservingFlags(entities: List<fr.descentecanyon.app.data.local.entity.CanyonEntity>) {
        val merged = entities.map { entity ->
            val existing = canyonDao.getById(entity.id)
            if (existing != null) {
                entity.copy(
                    isFavorite = existing.isFavorite,
                    isOffline = existing.isOffline,
                )
            } else {
                entity
            }
        }
        canyonDao.insertAll(merged)
    }
}
