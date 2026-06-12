package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.TrackedActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NotificationSyncEngineTest {

    @Test
    fun `followed canyon only emits unseen debit`() {
        val baselineDebit = debit(date = LocalDate.of(2026, 6, 10), niveau = NiveauDebit.CORRECT)
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(
                    canyonId = 42,
                    canyonName = "Riolan",
                    baselineDebits = listOf(baselineDebit),
                )
            )
        )

        val (updatedState, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = listOf(
                debit(date = LocalDate.of(2026, 6, 12), niveau = NiveauDebit.GROS),
                baselineDebit,
            ),
            activeTopics = emptyList(),
            nowEpochMs = 123L,
        )

        assertEquals(1, summary.newDebitEvents.size)
        assertTrue(summary.newForumEvents.isEmpty())
        assertEquals(TrackedActivityType.DEBIT, summary.newDebitEvents.first().type)
        assertEquals("Riolan", summary.newDebitEvents.first().title)
        assertEquals(2, updatedState.followedCanyons.first().seenDebitKeys.size)
        assertEquals(true, updatedState.followedCanyons.first().hasSeededLatestDebits)
        assertEquals(1, updatedState.recentEvents.size)
    }

    @Test
    fun `first sync after empty canyon follow seeds without notifying`() {
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(
                    canyonId = 42,
                    canyonName = "Riolan",
                    baselineDebits = emptyList(),
                )
            )
        )

        val (updatedState, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = listOf(debit(date = LocalDate.of(2026, 6, 12), niveau = NiveauDebit.GROS)),
            activeTopics = emptyList(),
            nowEpochMs = 789L,
        )

        assertTrue(summary.newDebitEvents.isEmpty())
        assertEquals(true, updatedState.followedCanyons.first().hasSeededLatestDebits)
        assertEquals(1, updatedState.followedCanyons.first().seenDebitKeys.size)
    }

    @Test
    fun `followed forum category only emits unseen topic marker`() {
        val existingTopic = topic(
            topicId = 10,
            forumId = 16,
            forumName = "SUISSE",
            lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=100#p100",
        )
        val state = NotificationCenterState(
            followedForumCategories = listOf(
                NotificationSyncEngine.buildInitialForumFollow(
                    forumId = 16,
                    forumName = "SUISSE",
                    baselineTopics = listOf(existingTopic),
                )
            )
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = emptyList(),
            activeTopics = listOf(
                existingTopic,
                topic(
                    topicId = 10,
                    forumId = 16,
                    forumName = "SUISSE",
                    lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=101#p101",
                ),
            ),
            nowEpochMs = 456L,
        )

        assertTrue(summary.newDebitEvents.isEmpty())
        assertEquals(1, summary.newForumEvents.size)
        assertEquals(TrackedActivityType.FORUM, summary.newForumEvents.first().type)
        assertEquals("Sujet test", summary.newForumEvents.first().title)
    }

    @Test
    fun `forum thread and category do not duplicate same message`() {
        val trackedTopic = topic(
            topicId = 10,
            forumId = 16,
            forumName = "SUISSE",
            lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=100#p100",
        )
        val state = NotificationCenterState(
            followedForumCategories = listOf(
                NotificationSyncEngine.buildInitialForumFollow(
                    forumId = 16,
                    forumName = "SUISSE",
                    baselineTopics = listOf(trackedTopic),
                )
            ),
            followedForumThreads = listOf(
                NotificationSyncEngine.buildInitialForumThreadFollow(
                    topic = trackedTopic,
                    baselineTopics = listOf(trackedTopic),
                )
            ),
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = emptyList(),
            activeTopics = listOf(
                topic(
                    topicId = 10,
                    forumId = 16,
                    forumName = "SUISSE",
                    lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=101#p101",
                )
            ),
            nowEpochMs = 999L,
        )

        assertEquals(1, summary.newForumEvents.size)
    }

    private fun debit(
        date: LocalDate,
        niveau: NiveauDebit,
    ): Debit {
        return Debit(
            canyonId = 42,
            canyonNom = "Riolan",
            date = date,
            niveau = niveau,
            auteur = "antoine",
        )
    }

    private fun topic(
        topicId: Int,
        forumId: Int,
        forumName: String,
        lastMessageUrl: String,
    ): ForumActiveTopic {
        return ForumActiveTopic(
            topicId = topicId,
            title = "Sujet test",
            forumId = forumId,
            forumName = forumName,
            replyCount = 4,
            viewCount = 120,
            lastAuthor = "Max38",
            lastPostedAtText = "ven. 12 juin 2026 10:42",
            lastPostedAtEpochMs = 1_718_184_520_000,
            topicUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=$forumId&t=$topicId",
            lastMessageUrl = lastMessageUrl,
        )
    }
}
