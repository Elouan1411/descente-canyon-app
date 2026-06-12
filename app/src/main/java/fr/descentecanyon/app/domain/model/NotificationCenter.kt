package fr.descentecanyon.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationCenterState(
    val installOffsetMinutes: Int? = null,
    val followedCanyons: List<FollowedCanyon> = emptyList(),
    val followedForumCategories: List<FollowedForumCategory> = emptyList(),
    val recentEvents: List<TrackedActivityEvent> = emptyList(),
) {
    fun hasTrackedTargets(): Boolean = followedCanyons.isNotEmpty() || followedForumCategories.isNotEmpty()
}

@Serializable
data class FollowedCanyon(
    val canyonId: Int,
    val canyonName: String,
    val seenDebitKeys: List<String> = emptyList(),
)

@Serializable
data class FollowedForumCategory(
    val key: String,
    val forumId: Int? = null,
    val forumName: String,
    val seenTopicMarkers: List<String> = emptyList(),
)

@Serializable
data class TrackedActivityEvent(
    val id: String,
    val type: TrackedActivityType,
    val title: String,
    val body: String,
    val occurredAtEpochMs: Long,
    val canyonId: Int? = null,
    val canyonName: String? = null,
    val forumName: String? = null,
    val externalUrl: String? = null,
)

@Serializable
enum class TrackedActivityType {
    DEBIT,
    FORUM,
}

data class NotificationSyncSummary(
    val newDebitEvents: List<TrackedActivityEvent> = emptyList(),
    val newForumEvents: List<TrackedActivityEvent> = emptyList(),
) {
    val hasUpdates: Boolean = newDebitEvents.isNotEmpty() || newForumEvents.isNotEmpty()
}

fun forumCategoryKey(forumId: Int?, forumName: String): String {
    return forumId?.let { "forum:$it" } ?: "forum-name:${forumName.trim().lowercase()}"
}
