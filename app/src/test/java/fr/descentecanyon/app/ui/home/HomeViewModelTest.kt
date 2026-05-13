package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.repository.HomeFeedSnapshotStore
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val debitRepository = mockk<DebitRepository>()
    private val forumRepository = mockk<ForumRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val appMetadataDao = mockk<AppMetadataDao>()
    private val snapshotStore = HomeFeedSnapshotStore(appMetadataDao)

    @Test
    fun `offline startup without cached data shows offline empty states`() = runTest {
        val connectivity = MutableStateFlow(false)
        var currentOnline = false
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns null
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(HomeFeedNotice.OFFLINE_EMPTY, viewModel.uiState.value.debitFeed.notice)
        assertEquals(HomeFeedNotice.OFFLINE_EMPTY, viewModel.uiState.value.forumFeed.notice)
        assertTrue(viewModel.uiState.value.debitFeed.items.isEmpty())
        assertTrue(viewModel.uiState.value.forumFeed.items.isEmpty())
    }

    @Test
    fun `going offline with loaded debits keeps list and shows banner`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns null
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        currentOnline = false
        connectivity.value = false
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.debitFeed.items.size)
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.debitFeed.notice)
    }

    @Test
    fun `forum service failure without cache shows service unavailable`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns AppMetadataEntity("home.selected_feed_type", HomeFeedType.FORUM.name)
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.failure(IllegalStateException("500"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(HomeFeedNotice.SERVICE_UNAVAILABLE, viewModel.uiState.value.forumFeed.notice)
    }

    @Test
    fun `network failure with cached forum keeps cached topics and shows offline banner`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns AppMetadataEntity("home.selected_feed_type", HomeFeedType.FORUM.name)
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(listOf(sampleTopic()), 5678L)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.failure(UnknownHostException("Unable to resolve host"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.forumFeed.notice)
    }

    @Test
    fun `selecting forum persists selection and refreshes forum feed`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns null
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        coVerify {
            appMetadataDao.insert(
                match { metadata ->
                    metadata.key == "home.selected_feed_type" && metadata.value == HomeFeedType.FORUM.name
                }
            )
        }
    }

    @Test
    fun `restored selected feed does not overwrite user selection made while cache is loading`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        val cacheRestoreGate = CompletableDeferred<Unit>()
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns AppMetadataEntity("home.selected_feed_type", HomeFeedType.DEBITS.name)
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } coAnswers {
            cacheRestoreGate.await()
            CachedItems(listOf(sampleDebit()), 1234L)
        }
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(listOf(sampleTopic()), 5678L)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()
        cacheRestoreGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
    }

    @Test
    fun `selecting current forum feed refreshes topics`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        coEvery { appMetadataDao.get("home.selected_feed_type") } returns AppMetadataEntity("home.selected_feed_type", HomeFeedType.FORUM.name)
        coEvery { appMetadataDao.insert(any()) } returns Unit
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        coVerify(atLeast = 2) { forumRepository.refreshActiveTopics(any()) }
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            debitRepository = debitRepository,
            forumRepository = forumRepository,
            connectivityObserver = connectivityObserver,
            snapshotStore = snapshotStore,
        )
    }

    private fun sampleDebit() = Debit(
        canyonId = 27,
        canyonNom = "Furon",
        date = LocalDate.of(2026, 3, 28),
        niveau = NiveauDebit.CORRECT,
        auteur = "Alice",
    )

    private fun sampleTopic() = ForumActiveTopic(
        topicId = 28125,
        title = "Baisse des notes Gamchi , Trummel IV",
        forumId = 16,
        forumName = "SUISSE",
        replyCount = 34,
        viewCount = 34624,
        lastAuthor = "Max38",
        lastPostedAtText = "ven. 03 avr. 2026 22:20",
        lastPostedAtEpochMs = 1_743_800_454_000,
        topicUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125",
        lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125&p=305248#p305248",
    )
}
