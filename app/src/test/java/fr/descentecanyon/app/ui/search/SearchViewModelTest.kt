package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val searchCanyonsUseCase = SearchCanyonsUseCase(canyonRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `full local catalog is available without query`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, pays = "France", departement = "Ain"),
                canyon(id = 2, pays = "Espagne", departement = "Huesca"),
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle())

        advanceUntilIdle()

        assertEquals(setOf(1, 2), viewModel.uiState.value.results.map { it.id }.toSet())
        assertEquals(listOf("Espagne", "France"), viewModel.uiState.value.availableCountries)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `filters narrow results and changing country resets department`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, pays = "France", departement = "Ain"),
                canyon(id = 2, pays = "France", departement = "Isere", isFavorite = true),
                canyon(id = 3, pays = "Espagne", departement = "Huesca"),
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle())

        advanceUntilIdle()
        viewModel.onCriteriaChanged(
            SearchCriteria(
                selectedCountry = "France",
                selectedDepartment = "Isere",
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.results.map { it.id })

        viewModel.onCriteriaChanged(viewModel.uiState.value.criteria.copy(selectedCountry = "Espagne"))
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.criteria.selectedDepartment)
        assertEquals(listOf(3), viewModel.uiState.value.results.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `reselecting same sort toggles direction`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Alpha", interet = 2f),
                canyon(id = 2, nom = "Beta", interet = 4f),
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle())

        advanceUntilIdle()
        viewModel.onSortSelected(SearchSortField.NAME)
        advanceUntilIdle()
        assertEquals(SortDirection.ASC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(1, 2), viewModel.uiState.value.results.map { it.id })

        viewModel.onSortSelected(SearchSortField.NAME)
        advanceUntilIdle()
        assertEquals(SortDirection.DESC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(2, 1), viewModel.uiState.value.results.map { it.id })
    }

    private fun canyon(
        id: Int,
        nom: String = "Canyon $id",
        pays: String = "France",
        departement: String? = "Ain",
        interet: Float? = 3f,
        isFavorite: Boolean = false,
    ) = CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = pays,
        departement = departement,
        cotation = "v3a3III",
        cotationRating = CotationRating.parse("v3a3III"),
        interet = interet,
        isFavorite = isFavorite,
        url = "/canyoning/canyon/$id/test.html",
        searchableText = "$nom $pays ${departement.orEmpty()}".lowercase(),
    )
}
