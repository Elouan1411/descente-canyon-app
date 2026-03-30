package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.di.DefaultDispatcher
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `broad catalog is deferred while keeping available filters`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, pays = "France", departement = "Ain"),
                canyon(id = 2, pays = "Espagne", departement = "Huesca"),
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher)

        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), viewModel.uiState.value.results.map { it.id })
        assertEquals(true, viewModel.uiState.value.isResultListDeferred)
        assertEquals(2, viewModel.uiState.value.totalResultsCount)
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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher)

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onCriteriaChanged(SearchCriteria(selectedCountry = "France", selectedDepartment = "Isere"))
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.results.map { it.id })
        assertEquals(1, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onCriteriaChanged(viewModel.uiState.value.criteria.copy(selectedCountry = "Espagne"))
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.criteria.selectedDepartment)
        assertEquals(listOf(3), viewModel.uiState.value.results.map { it.id })
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)
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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher)

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onQueryChanged("al")
        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onSortSelected(SearchSortField.NAME)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(SortDirection.ASC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(1), viewModel.uiState.value.results.map { it.id })
        assertEquals(1, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onSortSelected(SearchSortField.NAME)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(SortDirection.DESC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(1), viewModel.uiState.value.results.map { it.id })
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `selecting a canyon does not request a list scroll reset`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(canyon(id = 1)))
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher)

        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)

        viewModel.selectCanyon(1)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `location update enables distance sort and map selection`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Alpha", latitude = 43.70, longitude = 6.90),
                canyon(id = 2, nom = "Beta", latitude = 43.90, longitude = 7.30),
            )
        )
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher)

        advanceTimeBy(250)
        advanceUntilIdle()

        viewModel.onLocationPermissionResult(true)
        viewModel.onUserLocationUpdated(43.705, 6.905)
        viewModel.onSortSelected(SearchSortField.DISTANCE)
        viewModel.onResultViewModeChanged(SearchResultViewMode.MAP)
        viewModel.selectCanyon(1)
        advanceTimeBy(250)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLocationPermission)
        assertEquals(SearchSortField.DISTANCE, viewModel.uiState.value.criteria.sortField)
        assertEquals(listOf(1, 2), viewModel.uiState.value.results.map { it.id })
        assertEquals(SearchResultViewMode.MAP, viewModel.uiState.value.resultViewMode)
        assertEquals(1, viewModel.uiState.value.selectedCanyon?.id)

        viewModel.clearSelectedCanyon()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedCanyon)
    }

    private fun canyon(
        id: Int,
        nom: String = "Canyon $id",
        pays: String = "France",
        departement: String? = "Ain",
        interet: Float? = 3f,
        isFavorite: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
    ) = CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = pays,
        countryTokens = pays.split(',').map(String::trim),
        departement = departement,
        departmentTokens = departement?.split(',')?.map(String::trim).orEmpty(),
        cotation = "v3a3III",
        cotationRating = CotationRating.parse("v3a3III"),
        interet = interet,
        isFavorite = isFavorite,
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id/test.html",
        searchableText = "$nom $pays ${departement.orEmpty()}".lowercase(),
        normalizedNom = nom.lowercase(),
        normalizedNomComplet = nom.lowercase(),
    )
}
