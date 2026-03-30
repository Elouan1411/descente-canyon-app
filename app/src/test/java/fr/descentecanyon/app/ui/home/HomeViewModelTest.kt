package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.usecase.GetLatestDebitsUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    private val connectivityObserver = mockk<ConnectivityObserver>()

    @Test
    fun `offline startup without data shows offline empty state`() = runTest {
        val connectivity = MutableStateFlow(false)
        var currentOnline = false
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }

        val viewModel = HomeViewModel(GetLatestDebitsUseCase(debitRepository), connectivityObserver)
        advanceUntilIdle()

        assertEquals(HomeLatestDebitsNotice.OFFLINE_EMPTY, viewModel.uiState.value.latestDebitsNotice)
        assertTrue(viewModel.uiState.value.latestDebits.isEmpty())
    }

    @Test
    fun `going offline with loaded data keeps list and shows banner`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        every { debitRepository.getLatestDebits(any()) } returns flowOf(Result.success(listOf(sampleDebit())))

        val viewModel = HomeViewModel(GetLatestDebitsUseCase(debitRepository), connectivityObserver)
        advanceUntilIdle()

        currentOnline = false
        connectivity.value = false
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.latestDebits.size)
        assertEquals(HomeLatestDebitsNotice.OFFLINE_BANNER, viewModel.uiState.value.latestDebitsNotice)
    }

    @Test
    fun `online service failure without data shows service unavailable state`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        every { debitRepository.getLatestDebits(any()) } returns flowOf(Result.failure(IllegalStateException("500")))

        val viewModel = HomeViewModel(GetLatestDebitsUseCase(debitRepository), connectivityObserver)
        advanceUntilIdle()

        assertEquals(HomeLatestDebitsNotice.SERVICE_UNAVAILABLE, viewModel.uiState.value.latestDebitsNotice)
    }

    @Test
    fun `reconnection automatically reloads latest debits`() = runTest {
        val connectivity = MutableStateFlow(false)
        var currentOnline = false
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        every { debitRepository.getLatestDebits(any()) } returns flowOf(Result.success(listOf(sampleDebit())))

        val viewModel = HomeViewModel(GetLatestDebitsUseCase(debitRepository), connectivityObserver)
        advanceUntilIdle()
        assertEquals(HomeLatestDebitsNotice.OFFLINE_EMPTY, viewModel.uiState.value.latestDebitsNotice)

        currentOnline = true
        connectivity.value = true
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.latestDebits.size)
        assertEquals(null, viewModel.uiState.value.latestDebitsNotice)
    }

    @Test
    fun `network failure while online is still treated as offline state`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        every { debitRepository.getLatestDebits(any()) } returns flowOf(Result.failure(UnknownHostException("Unable to resolve host")))

        val viewModel = HomeViewModel(GetLatestDebitsUseCase(debitRepository), connectivityObserver)
        advanceUntilIdle()

        assertEquals(HomeLatestDebitsNotice.OFFLINE_EMPTY, viewModel.uiState.value.latestDebitsNotice)
    }

    private fun sampleDebit() = Debit(
        canyonId = 27,
        canyonNom = "Furon",
        date = LocalDate.of(2026, 3, 28),
        niveau = NiveauDebit.CORRECT,
        auteur = "Alice",
    )
}
