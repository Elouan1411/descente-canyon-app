package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchCanyonsUseCase @Inject constructor(
    private val canyonRepository: CanyonRepository,
) {
    operator fun invoke(query: String): Flow<Result<List<CanyonSummary>>> {
        return canyonRepository.searchByName(query)
    }
}
