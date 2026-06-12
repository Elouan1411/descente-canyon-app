package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.repository.ForumRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class ForumRepositoryImpl @Inject constructor(
    private val scraper: CanyonScraper,
    private val snapshotStore: HomeFeedSnapshotStore,
) : ForumRepository {

    override fun getActiveTopics(limit: Int): Flow<Result<List<ForumActiveTopic>>> {
        return flow {
            emit(refreshActiveTopics(limit).map { it.items })
        }
    }

    override suspend fun getCachedActiveTopics(limit: Int): CachedItems<ForumActiveTopic> {
        return snapshotStore.readActiveTopics(limit)
    }

    override suspend fun refreshActiveTopics(limit: Int): Result<CachedItems<ForumActiveTopic>> {
        return scraper.scrapeActiveForumTopics().map { scrapedTopics ->
            val topics = scrapedTopics.take(limit).map { it.toDomain() }
            val syncedAtEpochMs = System.currentTimeMillis()
            snapshotStore.writeActiveTopics(topics, syncedAtEpochMs)
            CachedItems(items = topics, syncedAtEpochMs = syncedAtEpochMs)
        }
    }

    override suspend fun getCachedCategories(): CachedItems<ForumCategory> {
        return snapshotStore.readForumCategories()
    }

    override suspend fun refreshCategories(): Result<CachedItems<ForumCategory>> {
        return scraper.scrapeForumCategories().map { scrapedCategories ->
            val categories = scrapedCategories.map { it.toDomain() }
            val syncedAtEpochMs = System.currentTimeMillis()
            snapshotStore.writeForumCategories(categories, syncedAtEpochMs)
            CachedItems(items = categories, syncedAtEpochMs = syncedAtEpochMs)
        }
    }
}
