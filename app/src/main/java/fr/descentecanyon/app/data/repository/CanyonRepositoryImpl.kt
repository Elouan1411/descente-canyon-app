package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.mapper.toDetail
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.repository.CanyonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CanyonRepositoryImpl @Inject constructor(
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val debitDao: DebitDao,
    private val photoDao: PhotoDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val scraper: CanyonScraper,
) : CanyonRepository {

    override fun searchByName(query: String): Flow<Result<List<CanyonSummary>>> {
        return canyonDao.searchByName(query).map { entities ->
            Result.success(entities.map { it.toSummary() })
        }
    }

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
        return if (localCanyon != null) {
            Result.success(loadLocalDetail(canyonId, localCanyon))
        } else {
            Result.failure(IllegalArgumentException("Canyon introuvable: $canyonId"))
        }
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
            ?: return Result.failure(IllegalArgumentException("Canyon introuvable: $canyonId"))

        return runCatching {
            val existingPhotos = photoDao.getByCanyonId(canyonId)
            val existingDebits = debitDao.getByCanyonId(canyonId).firstOrNull().orEmpty()

            val photos = scraper.scrapeCanyonPhotos(canyonId)
                .getOrNull()
                ?.map { it.toEntity() }
                ?: existingPhotos
            val debits = scraper.scrapeCanyonDebits(canyonId)
                .getOrNull()
                ?.map { it.toEntity() }
                ?: existingDebits
            replaceDynamicData(canyonId = canyonId, photoEntities = photos, debitEntities = debits)

            loadLocalDetail(canyonId, canyonDao.getById(canyonId) ?: localCanyon)
        }.recoverCatching {
            loadLocalDetail(canyonId, localCanyon)
        }
    }

    override fun getCanyonsNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Flow<Result<List<CanyonSummary>>> {
        return flow {
            val representativePoints = geoPointDao.getAll()
                .groupBy { it.canyonId }
                .mapValues { (_, points) -> points.bestMarkerPoint() }

            if (representativePoints.isEmpty()) {
                emit(Result.success(emptyList()))
                return@flow
            }

            val canyons = canyonDao.getByIds(representativePoints.keys.toList())
            val nearby = canyons.mapNotNull { canyon ->
                val point = representativePoints[canyon.id] ?: return@mapNotNull null
                val distanceKm = haversineKm(
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

    private suspend fun replaceDynamicData(
        canyonId: Int,
        photoEntities: List<fr.descentecanyon.app.data.local.entity.PhotoEntity>,
        debitEntities: List<fr.descentecanyon.app.data.local.entity.DebitEntity>,
    ) {
        photoDao.deleteByCanyonId(canyonId)
        if (photoEntities.isNotEmpty()) {
            photoDao.insertAll(photoEntities)
        }

        debitDao.deleteByCanyonId(canyonId)
        if (debitEntities.isNotEmpty()) {
            debitDao.insertAll(debitEntities)
        }
    }

    private suspend fun loadLocalDetail(
        canyonId: Int,
        canyon: fr.descentecanyon.app.data.local.entity.CanyonEntity,
    ): CanyonDetail {
        val geoPoints = geoPointDao.getByCanyonId(canyonId)
        val bibliography = bibliographyDao.getByCanyonId(canyonId)
        val regulations = regulationDao.getByCanyonId(canyonId)
        val photos = photoDao.getByCanyonId(canyonId)
        val debits = debitDao.getByCanyonId(canyonId).firstOrNull().orEmpty()
        return canyon.toDetail(geoPoints, bibliography, regulations, photos, debits)
    }

    private fun List<GeoPointEntity>.bestMarkerPoint(): GeoPointEntity {
        return bestMarkerPointOrNull() ?: first()
    }

    private fun List<GeoPointEntity>.bestMarkerPointOrNull(): GeoPointEntity? {
        return minByOrNull { point ->
            when (runCatching { GeoPointType.valueOf(point.type) }.getOrDefault(GeoPointType.UNKNOWN)) {
                GeoPointType.PARKING_AMONT -> 0
                GeoPointType.PARKING_AVAL -> 1
                GeoPointType.ENTREE -> 2
                GeoPointType.SORTIE -> 3
                GeoPointType.POINT_REMARQUABLE -> 4
                GeoPointType.ECHAPPATOIRE -> 5
                GeoPointType.UNKNOWN -> 6
            }
        }
    }

    private fun haversineKm(
        latitude: Double,
        longitude: Double,
        targetLatitude: Double,
        targetLongitude: Double,
    ): Double {
        val earthRadiusKm = 6371.0
        val latDistance = Math.toRadians(targetLatitude - latitude)
        val lonDistance = Math.toRadians(targetLongitude - longitude)
        val a = sin(latDistance / 2).pow(2.0) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(targetLatitude)) *
            sin(lonDistance / 2).pow(2.0)

        return 2 * earthRadiusKm * asin(sqrt(a))
    }
}
