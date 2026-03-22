package fr.descentecanyon.app.domain.repository

interface MapOfflineRepository {
    suspend fun downloadRegion(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Result<Unit>
}
