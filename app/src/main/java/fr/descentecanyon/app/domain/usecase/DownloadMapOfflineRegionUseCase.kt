package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import javax.inject.Inject

class DownloadMapOfflineRegionUseCase @Inject constructor(
    private val mapOfflineRepository: MapOfflineRepository,
) {
    suspend operator fun invoke(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Result<Unit> {
        return mapOfflineRepository.downloadRegion(name, latitude, longitude, radiusKm)
    }
}
