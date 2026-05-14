package fr.descentecanyon.app.data.repository

import androidx.room.withTransaction
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebitRepositoryImpl @Inject constructor(
    private val database: DescenteCanyonDatabase,
    private val debitDao: DebitDao,
    private val scraper: CanyonScraper,
    private val snapshotStore: HomeFeedSnapshotStore,
) : DebitRepository {

    override fun getDebitsForCanyon(canyonId: Int): Flow<Result<List<Debit>>> {
        return debitDao.getByCanyonId(canyonId).map { entities ->
            Result.success(entities.map { it.toDomain() })
        }
    }

    override fun getLatestDebits(limit: Int): Flow<Result<List<Debit>>> {
        return flow {
            emit(refreshLatestDebits(limit).map { it.items })
        }
    }

    override suspend fun getCachedLatestDebits(limit: Int): CachedItems<Debit> {
        return snapshotStore.readLatestDebits(limit)
    }

    override suspend fun refreshLatestDebits(limit: Int): Result<CachedItems<Debit>> {
        return scraper.scrapeLatestDebits().map { scrapedDebits ->
            val allDebits = scrapedDebits.map { it.toDomain() }
            val debits = allDebits.take(limit)
            val syncedAtEpochMs = System.currentTimeMillis()
            snapshotStore.writeLatestDebits(allDebits, syncedAtEpochMs)
            CachedItems(items = debits, syncedAtEpochMs = syncedAtEpochMs)
        }
    }

    override suspend fun refreshDebits(canyonId: Int): Result<List<Debit>> {
        return scraper.scrapeCanyonDebits(
            canyonId = canyonId,
            timeoutMs = REFRESH_TIMEOUT_MS,
        ).map { scrapedDebits ->
            val entities = scrapedDebits.map { it.toEntity() }

            database.withTransaction {
                debitDao.deleteByCanyonId(canyonId)
                debitDao.insertAll(entities)
            }

            entities.map { it.toDomain() }
        }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 15_000
    }
}
