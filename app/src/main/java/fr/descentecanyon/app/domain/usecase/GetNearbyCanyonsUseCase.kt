package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetNearbyCanyonsUseCase @Inject constructor(
    private val canyonRepository: CanyonRepository,
) {
    operator fun invoke(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0,
    ): Flow<Result<List<CanyonSummary>>> {
        return canyonRepository.getCanyonsNearby(latitude, longitude, radiusKm)
    }
}
