package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.database.AppDatabase
import fr.descentecanyon.app.data.local.importer.EmbeddedCanyonDataImporter
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.mapper.toSearchItem
import fr.descentecanyon.app.data.mapper.toDetail
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CanyonRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val debitDao: DebitDao,
    private val photoDao: PhotoDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val embeddedCanyonDataImporter: EmbeddedCanyonDataImporter,
    private val scraper: CanyonScraper,
    private val mapOfflineRepository: MapOfflineRepository,
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

    override fun observeSearchCatalog(): Flow<List<CanyonSearchItem>> {
        return canyonDao.observeAll()
            .map { entities ->
                val representativePoints = geoPointDao.getAll()
                    .groupBy { it.canyonId }
                    .mapValues { (_, points) -> points.bestMarkerPointOrNull() }

                val baseItems = entities.map { entity ->
                    val point = representativePoints[entity.id]
                    entity.toSearchItem(
                        representativeLat = point?.latitude,
                        representativeLng = point?.longitude,
                    )
                }

                val knownCountryBySubdivision = baseItems
                    .asSequence()
                    .filter { it.countryTokens.size == 1 }
                    .flatMap { item ->
                        item.departmentTokens.asSequence().map { subdivision ->
                            subdivision.normalizeForSearch() to item.countryTokens.first()
                        }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapNotNull { (subdivision, countries) ->
                        countries.distinct().singleOrNull()?.let { subdivision to it }
                    }
                    .toMap()

                baseItems.map { item ->
                    item.copy(
                        subdivisionsByCountry = item.buildSubdivisionsByCountry(knownCountryBySubdivision),
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)

        return runCatching {
            val detail = scraper.scrapeFullCanyonDetail(canyonId).getOrThrow()
            val photos = scraper.scrapeCanyonPhotos(canyonId).getOrDefault(emptyList())
            val debits = scraper.scrapeCanyonDebits(canyonId).getOrDefault(emptyList())
            val entity = detail.toEntity()
            database.withTransaction {
                insertPreservingFlags(entity)
                replaceSupportingData(
                    canyonId = canyonId,
                    geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) },
                    photoEntities = photos.map { it.toEntity() },
                    debitEntities = debits.map { it.toEntity() },
                )
            }

            loadLocalDetail(canyonId, canyonDao.getById(canyonId) ?: entity)
        }.recoverCatching {
            if (localCanyon != null) {
                loadLocalDetail(canyonId, localCanyon)
            } else {
                throw it
            }
        }
    }

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
        if (localCanyon != null) {
            return Result.success(loadLocalDetail(canyonId, localCanyon))
        }

        return runCatching {
            val summary = scraper.scrapeCanyonSummary(canyonId).getOrThrow()
            val entity = summary.toEntity()
            insertPreservingFlags(entity)
            loadLocalDetail(canyonId, canyonDao.getById(canyonId) ?: entity)
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

    override suspend fun downloadForOffline(canyonId: Int): Result<Unit> = runCatching {
        val detail = scraper.scrapeFullCanyonDetail(canyonId).getOrThrow()
        val photos = scraper.scrapeCanyonPhotos(canyonId).getOrDefault(emptyList())
        val debits = scraper.scrapeCanyonDebits(canyonId).getOrDefault(emptyList())
        val entity = detail.toEntity().copy(isOffline = true)

        val geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) }
        val photoEntities = photos.map { it.toEntity() }
        val debitEntities = debits.map { it.toEntity() }
        database.withTransaction {
            insertPreservingFlags(entity)
            replaceSupportingData(canyonId, geoPointEntities, photoEntities, debitEntities)
        }

        geoPointEntities.bestMarkerPointOrNull()?.let { point ->
            mapOfflineRepository.downloadRegion(
                name = entity.nom,
                latitude = point.latitude,
                longitude = point.longitude,
                radiusKm = 3.0,
            ).getOrThrow()
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

    // --- B-4/B-5 fix: Preserve isFavorite and isOffline flags on insert ---

    /**
     * Insert or update a single entity while preserving existing user flags
     * and without deleting dependent rows linked by foreign keys.
     */
    internal suspend fun insertPreservingFlags(entity: fr.descentecanyon.app.data.local.entity.CanyonEntity) {
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

    /**
     * Insert or update a list of entities while preserving existing user flags
     * and without deleting dependent rows linked by foreign keys.
     */
    internal suspend fun insertAllPreservingFlags(entities: List<fr.descentecanyon.app.data.local.entity.CanyonEntity>) {
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

    private suspend fun replaceSupportingData(
        canyonId: Int,
        geoPointEntities: List<GeoPointEntity>,
        photoEntities: List<fr.descentecanyon.app.data.local.entity.PhotoEntity>,
        debitEntities: List<fr.descentecanyon.app.data.local.entity.DebitEntity>,
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

private fun CanyonSearchItem.buildSubdivisionsByCountry(
    knownCountryBySubdivision: Map<String, String>,
): Map<String, List<String>> {
    val countries = countryTokens.distinct()
    if (countries.isEmpty()) return emptyMap()

    val mapping = countries.associateWith { mutableListOf<String>() }.toMutableMap()
    val subdivisions = departmentTokens.distinct()
    if (subdivisions.isEmpty()) {
        return mapping.mapValues { emptyList() }
    }

    if (countries.size == 1) {
        mapping[countries.first()]?.addAll(subdivisions)
        return mapping.mapValues { (_, values) -> values.distinct() }
    }

    val unresolved = mutableListOf<String>()
    subdivisions.forEach { subdivision ->
        val inferredCountry = knownCountryBySubdivision[subdivision.normalizeForSearch()]
        val matchedCountry = countries.firstOrNull { it.equals(inferredCountry, ignoreCase = true) }
        if (matchedCountry != null) {
            mapping.getValue(matchedCountry).add(subdivision)
        } else {
            unresolved += subdivision
        }
    }

    val emptyCountries = countries.filter { mapping.getValue(it).isEmpty() }
    when {
        unresolved.isNotEmpty() && emptyCountries.size == 1 -> {
            mapping.getValue(emptyCountries.first()).addAll(unresolved)
        }

        unresolved.size == emptyCountries.size -> {
            unresolved.zip(emptyCountries).forEach { (subdivision, country) ->
                mapping.getValue(country).add(subdivision)
            }
        }
    }

    return mapping.mapValues { (_, values) -> values.distinct() }
}
