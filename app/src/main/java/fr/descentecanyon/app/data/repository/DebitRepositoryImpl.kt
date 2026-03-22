package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebitRepositoryImpl @Inject constructor(
    private val debitDao: DebitDao,
    private val scraper: CanyonScraper,
) : DebitRepository {

    override fun getDebitsForCanyon(canyonId: Int): Flow<Result<List<Debit>>> {
        return debitDao.getByCanyonId(canyonId).map { entities ->
            Result.success(entities.map { it.toDomain() })
        }
    }

    override fun getLatestDebits(limit: Int): Flow<Result<List<Debit>>> {
        return debitDao.getLatest(limit).map { entities ->
            Result.success(entities.map { it.toDomain() })
        }
    }

    override suspend fun refreshDebits(canyonId: Int): Result<List<Debit>> {
        // TODO: Scrape debits page and update local DB
        return Result.success(emptyList())
    }
}
