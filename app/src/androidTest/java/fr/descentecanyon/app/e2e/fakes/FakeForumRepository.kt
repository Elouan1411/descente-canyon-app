package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.repository.ForumRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeForumRepository @Inject constructor() : ForumRepository {
    override fun getActiveTopics(limit: Int): Flow<Result<List<ForumActiveTopic>>> {
        return flowOf(Result.success(emptyList()))
    }

    override suspend fun getCachedActiveTopics(limit: Int): CachedItems<ForumActiveTopic> {
        return CachedItems()
    }

    override suspend fun refreshActiveTopics(limit: Int): Result<CachedItems<ForumActiveTopic>> {
        return Result.success(CachedItems())
    }
}
