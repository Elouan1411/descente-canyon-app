package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SearchUiState(
    val criteria: SearchCriteria = SearchCriteria(),
    val results: List<CanyonSearchItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val availableCountries: List<String> = emptyList(),
    val availableDepartments: List<String> = emptyList(),
    val activeFilterCount: Int = 0,
    val totalResultsCount: Int = 0,
    val isResultListDeferred: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchCanyonsUseCase: SearchCanyonsUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val criteriaFlow = MutableStateFlow(loadCriteria())
    private val _uiState = MutableStateFlow(
        SearchUiState(
            criteria = criteriaFlow.value,
            activeFilterCount = criteriaFlow.value.activeFilterCount(),
        )
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(searchCanyonsUseCase.observeCatalog(), criteriaFlow) { catalog, criteria ->
                val resultSet = searchCanyonsUseCase(catalog, criteria)
                SearchUiState(
                    criteria = criteria,
                    results = resultSet.results,
                    isLoading = false,
                    error = null,
                    availableCountries = resultSet.availableCountries,
                    availableDepartments = resultSet.availableDepartments,
                    activeFilterCount = criteria.activeFilterCount(),
                    totalResultsCount = resultSet.totalResultsCount,
                    isResultListDeferred = resultSet.isResultListDeferred,
                )
                }
                .collect { state ->
                _uiState.value = state
            }
        }
    }

    fun onQueryChanged(query: String) {
        onCriteriaChanged(criteriaFlow.value.copy(query = query))
    }

    fun onCriteriaChanged(criteria: SearchCriteria) {
        val current = criteriaFlow.value
        val updated = if (
            !current.selectedCountry.equals(criteria.selectedCountry, ignoreCase = true) &&
            current.selectedDepartment == criteria.selectedDepartment
        ) {
            criteria.copy(selectedDepartment = null)
        } else {
            criteria
        }
        criteriaFlow.value = updated
        persistCriteria(updated)
    }

    fun onSortSelected(field: SearchSortField) {
        val current = criteriaFlow.value
        val next = if (current.sortField == field) {
            current.copy(sortDirection = current.sortDirection.toggle())
        } else {
            current.copy(
                sortField = field,
                sortDirection = field.defaultDirection(),
            )
        }
        onCriteriaChanged(next)
    }

    fun clearAllFilters() {
        onCriteriaChanged(criteriaFlow.value.clearAllFilters())
    }

    fun clearCountry() {
        onCriteriaChanged(criteriaFlow.value.copy(selectedCountry = null, selectedDepartment = null))
    }

    fun clearDepartment() {
        onCriteriaChanged(criteriaFlow.value.copy(selectedDepartment = null))
    }

    fun clearQuery() {
        onCriteriaChanged(criteriaFlow.value.copy(query = ""))
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun loadCriteria(): SearchCriteria {
        val raw = savedStateHandle.get<String>(SEARCH_CRITERIA_KEY).orEmpty()
        return runCatching { json.decodeFromString<SearchCriteria>(raw) }
            .getOrDefault(SearchCriteria())
    }

    private fun persistCriteria(criteria: SearchCriteria) {
        savedStateHandle[SEARCH_CRITERIA_KEY] = json.encodeToString(criteria)
    }

    private fun SortDirection.toggle(): SortDirection {
        return if (this == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
    }

    private fun SearchSortField.defaultDirection(): SortDirection {
        return when (this) {
            SearchSortField.NAME, SearchSortField.DISTANCE -> SortDirection.ASC
            SearchSortField.RELEVANCE,
            SearchSortField.INTEREST,
            SearchSortField.POPULARITY,
            SearchSortField.DIFFICULTY,
            SearchSortField.ELEVATION,
            SearchSortField.LENGTH,
            SearchSortField.MAX_WATERFALL,
            -> SortDirection.DESC
        }
    }

    companion object {
        private const val SEARCH_CRITERIA_KEY = "search_criteria"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
