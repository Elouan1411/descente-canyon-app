package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import kotlinx.coroutines.flow.Flow

interface ForumRepository {

    fun getActiveTopics(limit: Int = 10): Flow<Result<List<ForumActiveTopic>>>

    suspend fun getCachedActiveTopics(limit: Int = 10): CachedItems<ForumActiveTopic>

    suspend fun refreshActiveTopics(limit: Int = 10): Result<CachedItems<ForumActiveTopic>>
}
