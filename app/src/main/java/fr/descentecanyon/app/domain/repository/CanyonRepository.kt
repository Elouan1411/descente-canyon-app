package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for canyon data access.
 * Implementations handle local access to the embedded canyon catalog.
 */
interface CanyonRepository {

    /**
     * Search canyons by name.
     * Returns results from the local embedded catalog.
     */
    fun searchByName(query: String): Flow<Result<List<CanyonSummary>>>

    /**
     * Get full canyon detail by ID.
     * Loads from the local embedded catalog.
     */
    suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail>

    /**
     * Get full canyon detail by ID.
     * Loads from the local embedded catalog.
     */
    suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail>

    /**
     * Get canyons near a geographic position.
     */
    fun getCanyonsNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0,
    ): Flow<Result<List<CanyonSummary>>>

    /**
     * Legacy no-op kept for compatibility.
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
