package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val canyonDao: CanyonDao,
) : FavoritesRepository {

    override fun getFavorites(): Flow<List<CanyonSummary>> {
        return canyonDao.getFavorites().map { entities ->
            entities.map { it.toSummary() }
        }
    }

    override suspend fun addFavorite(canyonId: Int) {
        canyonDao.setFavorite(canyonId, true)
    }

    override suspend fun removeFavorite(canyonId: Int) {
        canyonDao.setFavorite(canyonId, false)
    }

    override fun isFavorite(canyonId: Int): Flow<Boolean> {
        return canyonDao.isFavorite(canyonId).map { it ?: false }
    }
}
