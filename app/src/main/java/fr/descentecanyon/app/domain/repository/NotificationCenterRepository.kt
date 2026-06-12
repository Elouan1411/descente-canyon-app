package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.NotificationSyncSummary
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import kotlinx.coroutines.flow.Flow

interface NotificationCenterRepository {

    fun observeState(): Flow<NotificationCenterState>

    fun observeIsCanyonFollowed(canyonId: Int): Flow<Boolean>

    fun observeIsForumCategoryFollowed(forumId: Int?, forumName: String): Flow<Boolean>

    fun observeIsForumThreadFollowed(topicId: Int): Flow<Boolean>

    suspend fun toggleCanyonFollow(
        canyonId: Int,
        canyonName: String,
        baselineDebits: List<Debit>,
    )

    suspend fun removeCanyonFollow(canyonId: Int)

    suspend fun toggleForumCategoryFollow(
        forumId: Int?,
        forumName: String,
        baselineTopics: List<ForumActiveTopic>,
    )

    suspend fun removeForumCategoryFollow(key: String)

    suspend fun toggleForumThreadFollow(
        topic: ForumActiveTopic,
        baselineTopics: List<ForumActiveTopic>,
    )

    suspend fun removeForumThreadFollow(topicId: Int)

    suspend fun clearRecentActivity()

    suspend fun getOrCreateInstallOffsetMinutes(): Int

    suspend fun syncFetchedContent(
        latestDebits: List<Debit>,
        activeTopics: List<ForumActiveTopic>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): NotificationSyncSummary
}
