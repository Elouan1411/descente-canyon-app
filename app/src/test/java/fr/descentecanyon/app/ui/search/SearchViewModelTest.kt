package fr.descentecanyon.app.ui.search

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val searchCanyonsUseCase = SearchCanyonsUseCase(canyonRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `short query clears results without calling search`() = runTest {
        every { canyonRepository.searchByName(any()) } returns flowOf(Result.success(emptyList()))
        val viewModel = SearchViewModel(searchCanyonsUseCase)

        viewModel.onQueryChanged("r")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.results.isEmpty())
        verify(exactly = 0) { canyonRepository.searchByName(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `selected filters narrow fetched results`() = runTest {
        every { canyonRepository.searchByName("ri") } returns flowOf(
            Result.success(
                listOf(
                    summary(id = 1, pays = "France"),
                    summary(id = 2, pays = "Espagne"),
                    summary(id = 3, pays = "France", isOffline = true),
                )
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase)

        viewModel.onQueryChanged("ri")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), viewModel.uiState.value.results.map { it.id })
        assertEquals(listOf("Espagne", "France"), viewModel.uiState.value.availableCountries)

        viewModel.onCountrySelected("France")
        assertEquals(listOf(1, 3), viewModel.uiState.value.results.map { it.id })

        viewModel.onFilterSelected(SearchFilter.OFFLINE)
        assertEquals(listOf(3), viewModel.uiState.value.results.map { it.id })
        verify(exactly = 1) { canyonRepository.searchByName("ri") }
    }

    private fun summary(
        id: Int,
        pays: String,
        isOffline: Boolean = false,
    ) = CanyonSummary(
        id = id,
        nom = "Canyon $id",
        pays = pays,
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
        isOffline = isOffline,
    )
}
