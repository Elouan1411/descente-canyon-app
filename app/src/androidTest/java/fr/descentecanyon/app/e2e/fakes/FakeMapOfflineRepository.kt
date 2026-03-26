package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeMapOfflineRepository @Inject constructor() : MapOfflineRepository {
    override suspend fun downloadRegion(name: String, latitude: Double, longitude: Double, radiusKm: Double): Result<Unit> {
        return Result.success(Unit)
    }
}
