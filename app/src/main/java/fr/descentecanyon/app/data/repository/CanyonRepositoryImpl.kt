package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.dao.toDomain
import fr.descentecanyon.app.data.local.dao.getByIdsChunked
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.mapper.toSearchItem
import fr.descentecanyon.app.data.mapper.toSearchIndexEntity
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.CanyonPdfDao

@Singleton
class CanyonRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DescenteCanyonDatabase,
    private val canyonDao: CanyonDao,
    private val canyonPdfDao: CanyonPdfDao,
    private val localStore: CanyonLocalStore,
    private val geoPointDao: GeoPointDao,
    private val searchIndexDao: SearchIndexDao,
    private val watershedDao: WatershedDao,
    private val scraper: CanyonScraper,
    private val mapOfflineRepository: MapOfflineRepository,
    private val canyonPdfRepository: CanyonPdfRepository,
) : CanyonRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val searchCatalogFlow by lazy {
        searchIndexDao.observeAll()
            .map { rows ->
                rows.map { it.toSearchItem() }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .shareIn(
                scope = repositoryScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1,
            )
    }

    override fun observeSearchCatalog(): Flow<List<CanyonSearchItem>> {
        return searchCatalogFlow
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
            ?: return fetchAndCacheRemoteDetail(canyonId)

        return runCatching {
            localStore.loadLocalDetail(canyonId, localCanyon)
        }
    }

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
            ?: return Result.failure(IllegalArgumentException("Canyon introuvable: $canyonId"))

        return runCatching {
            localStore.loadLocalDetail(canyonId, localCanyon)
        }
    }

    override fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?> {
        return watershedDao.observeMetadataByCanyonId(canyonId).map { it?.toDomain() }
    }

    override suspend fun getWatershedGeometry(canyonId: Int): String? {
        return watershedDao.getGeometryByCanyonId(canyonId)
    }

    override fun getCanyonsNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<Result<List<CanyonSummary>>> {
        return flow {
            val representativePoints = geoPointDao.getAll()
                .groupBy { it.canyonId }
                .mapValues { (_, points) -> localStore.bestMarkerPoint(points) }

            if (representativePoints.isEmpty()) {
                emit(Result.success(emptyList()))
                return@flow
            }

            val canyons = canyonDao.getByIdsChunked(representativePoints.keys)
            val nearby = canyons.mapNotNull { canyon ->
                val point = representativePoints[canyon.id] ?: return@mapNotNull null
                val distanceKm = localStore.haversineKm(
                    latitude = latitude,
                    longitude = longitude,
                    targetLatitude = point.latitude,
                    targetLongitude = point.longitude,
                )
                if (distanceKm > radiusKm) return@mapNotNull null

                canyon.toSummary().copy(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    markerType = runCatching { GeoPointType.valueOf(point.type) }.getOrDefault(GeoPointType.UNKNOWN),
                ) to distanceKm
            }
                .sortedBy { (_, distanceKm) -> distanceKm }
                .map { (summary, _) -> summary }

            emit(Result.success(nearby))
        }
    }

    override suspend fun downloadForOffline(canyonId: Int): Result<Unit> = runCatching {
        val detail = scraper.scrapeFullCanyonDetail(canyonId).getOrThrow()
        val photos = scraper.scrapeCanyonPhotos(canyonId).getOrDefault(emptyList())
        val debits = scraper.scrapeCanyonDebits(canyonId).getOrDefault(emptyList())
        val entity = detail.toEntity().copy(isOffline = true)

        val existingGeoPoints = geoPointDao.getByCanyonId(canyonId)
        val geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) }.ifEmpty { existingGeoPoints }
        val photoEntities = photos.map { it.toEntity() }
        val debitEntities = debits.map { it.toEntity() }
        database.withTransaction {
            localStore.insertPreservingFlags(entity)
            localStore.replaceSupportingData(canyonId, geoPointEntities, photoEntities, debitEntities)
        }

        localStore.bestMarkerPointOrNull(geoPointEntities)?.let { point ->
            mapOfflineRepository.downloadRegion(
                name = entity.nom,
                latitude = point.latitude,
                longitude = point.longitude,
                radiusKm = 3.0,
            ).getOrThrow()
        }

        // Also sync and download community PDFs for offline access
        runCatching {
            canyonPdfRepository.syncPdfsForCanyon(canyonId)
            val pdfs = canyonPdfDao.getPdfsForCanyonSync(canyonId)
            for (pdf in pdfs) {
                if (!pdf.isDownloaded) {
                    canyonPdfRepository.downloadPdfFile(context, pdf)
                }
            }
        }
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
        return canyonDao.observeAll().map { entities ->
            Result.success(entities.map { it.toSummary() })
        }
    }

    private suspend fun fetchAndCacheRemoteDetail(canyonId: Int): Result<CanyonDetail> {
        return scraper.scrapeFullCanyonDetail(canyonId).mapCatching { detail ->
            val entity = detail.toEntity()
            val geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) }
            val representativePoint = localStore.bestMarkerPointOrNull(geoPointEntities)
            val searchIndex = entity.toSearchItem(
                representativeLat = representativePoint?.latitude,
                representativeLng = representativePoint?.longitude,
            ).toSearchIndexEntity()

            database.withTransaction {
                localStore.insertPreservingFlags(entity)
                localStore.replaceGeoPoints(canyonId, geoPointEntities)
                searchIndexDao.insertAll(listOf(searchIndex))
            }

            localStore.loadLocalDetail(canyonId, entity)
        }
    }

}
