package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class FakeCanyonRepository @Inject constructor() : CanyonRepository {
    override fun searchByName(query: String): Flow<Result<List<CanyonSummary>>> {
        val normalizedQuery = query.trim().lowercase()
        return flowOf(
            Result.success(
                E2eFixtureState.summaries().filter { summary ->
                    normalizedQuery.isBlank() || summary.nom.lowercase().contains(normalizedQuery)
                }
            )
        )
    }

    override fun observeSearchCatalog(): Flow<List<CanyonSearchItem>> {
        return E2eFixtureState.favoriteIds.map { E2eFixtureState.catalogItems() }
    }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        return E2eFixtureState.canyonDetails.value[canyonId]
            ?.let(Result.Companion::success)
            ?: Result.failure(IllegalArgumentException("Unknown canyon $canyonId"))
    }

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> = getCanyonDetail(canyonId)

    override fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?> = flowOf(null)

    override fun getCanyonsNearby(latitude: Double, longitude: Double, radiusKm: Double): Flow<Result<List<CanyonSummary>>> {
        return flowOf(Result.success(E2eFixtureState.summaries()))
    }

    override suspend fun downloadForOffline(canyonId: Int): Result<Unit> = Result.success(Unit)

    override suspend fun removeOfflineData(canyonId: Int): Result<Unit> = Result.success(Unit)

    override fun getOfflineCanyons(): Flow<List<CanyonSummary>> = flowOf(emptyList())

    override fun getCanyonsByLocation(locationPath: String): Flow<Result<List<CanyonSummary>>> {
        return flowOf(Result.success(E2eFixtureState.summaries()))
    }
}
