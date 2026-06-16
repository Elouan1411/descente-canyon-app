package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonWatershed
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for canyon data access.
 * Implementations load the embedded canyon catalog from local storage.
 */
interface CanyonRepository {

    /**
     * Observe the local search catalog enriched with filterable/searchable fields.
     */
    fun observeSearchCatalog(): Flow<List<CanyonSearchItem>>

    /**
     * Get full canyon detail by ID from the local catalog.
     */
    suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail>

    /**
     * Get a lightweight canyon preview before full detail is loaded.
     */
    suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail>

    /**
     * Observe watershed data imported asynchronously for a canyon.
     */
    fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?>

    /**
     * Load watershed polygon geometry only when a dedicated map screen needs it.
     */
    suspend fun getWatershedGeometry(canyonId: Int): String?

    /**
     * Get canyons near a geographic position.
     */
    fun getCanyonsNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0,
    ): Flow<Result<List<CanyonSummary>>>

    /**
     * Download a canyon for offline use (fiche + map tiles).
     */
    suspend fun downloadForOffline(canyonId: Int): Result<Unit>

    /**
     * Remove offline data for a canyon.
     */
    suspend fun removeOfflineData(canyonId: Int): Result<Unit>

    /**
     * Get all canyons saved for offline use.
     */
    fun getOfflineCanyons(): Flow<List<CanyonSummary>>

    /**
     * Get canyons by country/region path.
     */
    fun getCanyonsByLocation(locationPath: String): Flow<Result<List<CanyonSummary>>>
}
