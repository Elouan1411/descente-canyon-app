package fr.descentecanyon.app.data.repository

import androidx.room.withTransaction
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val database: DescenteCanyonDatabase,
    private val canyonDao: CanyonDao,
    private val searchIndexDao: SearchIndexDao,
) : FavoritesRepository {

    override fun getFavorites(): Flow<List<CanyonSummary>> {
        return canyonDao.getFavorites().map { entities ->
            entities.map { it.toSummary() }
        }
    }

    override suspend fun addFavorite(canyonId: Int) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            canyonDao.setFavorite(canyonId, true, now)
            searchIndexDao.setFavorite(canyonId, true)
        }
    }

    override suspend fun removeFavorite(canyonId: Int) {
        database.withTransaction {
            canyonDao.setFavorite(canyonId, false, null)
            searchIndexDao.setFavorite(canyonId, false)
        }
    }

    override fun isFavorite(canyonId: Int): Flow<Boolean> {
        return canyonDao.isFavorite(canyonId).map { it ?: false }
    }
}
