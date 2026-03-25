package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject

class DownloadCanyonOfflineUseCase @Inject constructor(
    private val canyonRepository: CanyonRepository,
) {
    suspend operator fun invoke(canyonId: Int): Result<Unit> {
        return canyonRepository.downloadForOffline(canyonId)
    }
}
