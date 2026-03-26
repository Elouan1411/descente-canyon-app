package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity
import fr.descentecanyon.app.data.mapper.toDetail
import fr.descentecanyon.app.domain.model.CanyonDetail
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

@Singleton
class CanyonLocalStore @Inject constructor(
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val debitDao: DebitDao,
    private val photoDao: PhotoDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val watershedDao: WatershedDao,
    private val representativePointSelector: RepresentativePointSelector,
) {

    suspend fun insertPreservingFlags(entity: CanyonEntity) {
        val existing = canyonDao.getById(entity.id)
        val merged = if (existing != null) {
            entity.copy(
                isFavorite = existing.isFavorite,
                isOffline = entity.isOffline || existing.isOffline,
                communesJson = entity.communesJson ?: existing.communesJson,
                bassin = entity.bassin ?: existing.bassin,
                coursEau = entity.coursEau ?: existing.coursEau,
                geologie = entity.geologie ?: existing.geologie,
                historique = entity.historique ?: existing.historique,
                remarques = entity.remarques ?: existing.remarques,
                hasSpecificRegulation = entity.hasSpecificRegulation || existing.hasSpecificRegulation,
                isForbidden = entity.isForbidden || existing.isForbidden,
            )
        } else {
            entity
        }
        if (canyonDao.insertIgnore(merged) == -1L) {
            canyonDao.update(merged)
        }
    }

    suspend fun insertAllPreservingFlags(entities: List<CanyonEntity>) {
        entities.forEach { entity ->
            val existing = canyonDao.getById(entity.id)
            val merged = if (existing != null) {
                entity.copy(
                    isFavorite = existing.isFavorite,
                    isOffline = existing.isOffline,
                    communesJson = entity.communesJson ?: existing.communesJson,
                    bassin = entity.bassin ?: existing.bassin,
                    coursEau = entity.coursEau ?: existing.coursEau,
                    geologie = entity.geologie ?: existing.geologie,
                    historique = entity.historique ?: existing.historique,
                    remarques = entity.remarques ?: existing.remarques,
                    hasSpecificRegulation = entity.hasSpecificRegulation || existing.hasSpecificRegulation,
                    isForbidden = entity.isForbidden || existing.isForbidden,
                )
            } else {
                entity
            }

            if (canyonDao.insertIgnore(merged) == -1L) {
                canyonDao.update(merged)
            }
        }
    }

    suspend fun replaceSupportingData(
        canyonId: Int,
        geoPointEntities: List<GeoPointEntity>,
        photoEntities: List<PhotoEntity>,
        debitEntities: List<DebitEntity>,
    ) {
        geoPointDao.deleteByCanyonId(canyonId)
        if (geoPointEntities.isNotEmpty()) {
            geoPointDao.insertAll(geoPointEntities)
        }

        photoDao.deleteByCanyonId(canyonId)
        if (photoEntities.isNotEmpty()) {
            photoDao.insertAll(photoEntities)
        }

        debitDao.deleteByCanyonId(canyonId)
        if (debitEntities.isNotEmpty()) {
            debitDao.insertAll(debitEntities)
        }
    }

    suspend fun loadLocalDetail(canyonId: Int, canyon: CanyonEntity): CanyonDetail {
        val geoPoints = geoPointDao.getByCanyonId(canyonId)
        val bibliography = bibliographyDao.getByCanyonId(canyonId)
        val regulations = regulationDao.getByCanyonId(canyonId)
        val photos = photoDao.getByCanyonId(canyonId)
        val debits = debitDao.getByCanyonId(canyonId).firstOrNull().orEmpty()
        val watershed = watershedDao.getByCanyonId(canyonId)
        return canyon.toDetail(geoPoints, bibliography, regulations, photos, debits, watershed)
    }

    suspend fun representativePointsByCanyon(): Map<Int, GeoPointEntity?> {
        return geoPointDao.getAll()
            .groupBy { it.canyonId }
            .mapValues { (_, points) -> representativePointSelector.bestMarkerPointOrNull(points) }
    }

    fun bestMarkerPoint(points: List<GeoPointEntity>): GeoPointEntity = representativePointSelector.bestMarkerPoint(points)

    fun bestMarkerPointOrNull(points: List<GeoPointEntity>): GeoPointEntity? = representativePointSelector.bestMarkerPointOrNull(points)

    fun haversineKm(
        latitude: Double,
        longitude: Double,
        targetLatitude: Double,
        targetLongitude: Double,
    ): Double = representativePointSelector.haversineKm(latitude, longitude, targetLatitude, targetLongitude)
}
