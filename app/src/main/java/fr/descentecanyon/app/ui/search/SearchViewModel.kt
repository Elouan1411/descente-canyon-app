package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.di.DefaultDispatcher
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SearchResultViewMode {
    LIST,
    MAP,
}

data class SearchLocationUiState(
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isLocating: Boolean = false,
)

data class SearchUiState(
    val queryDraft: String = "",
    val criteria: SearchCriteria = SearchCriteria(),
    val results: List<CanyonSearchItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val error: String? = null,
    val resultViewMode: SearchResultViewMode = SearchResultViewMode.LIST,
    val availableCountries: List<String> = emptyList(),
    val availableDepartments: List<String> = emptyList(),
    val activeFilterCount: Int = 0,
    val totalResultsCount: Int = 0,
    val isResultListDeferred: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isLocating: Boolean = false,
    val selectedCanyon: CanyonSearchItem? = null,
    val scrollResetRequestId: Int = 0,
)

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel @Inject constructor(
    private val searchCanyonsUseCase: SearchCanyonsUseCase,
    private val savedStateHandle: SavedStateHandle,
    @param:DefaultDispatcher private val searchDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val initialCriteria = loadCriteria()
    private val filtersFlow = MutableStateFlow(initialCriteria.sanitizedForPersistence().copy(query = ""))
    private val queryDraftFlow = MutableStateFlow(initialCriteria.query)
    private val appliedQueryFlow = MutableStateFlow(initialCriteria.query)
    private val locationFlow = MutableStateFlow(SearchLocationUiState())
    private val resultViewModeFlow = MutableStateFlow(SearchResultViewMode.LIST)
    private val selectedCanyonIdFlow = MutableStateFlow<Int?>(null)
    private val scrollResetRequestIdFlow = MutableStateFlow(0)
    private var queryDebounceJob: Job? = null

    private val _uiState = MutableStateFlow(
        SearchUiState(
            queryDraft = initialCriteria.query,
            criteria = initialCriteria,
            activeFilterCount = initialCriteria.activeFilterCount(),
        )
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                searchCanyonsUseCase.observeCatalog(),
                filtersFlow,
                appliedQueryFlow,
                locationFlow,
                resultViewModeFlow,
            ) { catalog, filters, appliedQuery, location, resultViewMode ->
                PartialSearchComputationInput(
                    catalog = catalog,
                    criteria = filters.copy(
                        query = appliedQuery,
                        userLatitude = location.userLatitude,
                        userLongitude = location.userLongitude,
                    ),
                    location = location,
                    resultViewMode = resultViewMode,
                )
            }
                .combine(scrollResetRequestIdFlow) { partial, scrollResetRequestId ->
                    partial.copy(scrollResetRequestId = scrollResetRequestId)
                }
                .combine(selectedCanyonIdFlow) { partial, selectedCanyonId ->
                    SearchComputationInput(
                        catalog = partial.catalog,
                        criteria = partial.criteria,
                        location = partial.location,
                        resultViewMode = partial.resultViewMode,
                        selectedCanyonId = selectedCanyonId,
                        scrollResetRequestId = partial.scrollResetRequestId,
                    )
                }
                .mapLatest { input ->
                    val resultSet = searchCanyonsUseCase(input.catalog, input.criteria)
                    SearchUiState(
                        queryDraft = queryDraftFlow.value,
                        criteria = input.criteria,
                        results = resultSet.results,
                        isLoading = false,
                        isSearching = false,
                        error = null,
                        resultViewMode = input.resultViewMode,
                        availableCountries = resultSet.availableCountries,
                        availableDepartments = resultSet.availableDepartments,
                        activeFilterCount = input.criteria.activeFilterCount(),
                        totalResultsCount = resultSet.totalResultsCount,
                        isResultListDeferred = resultSet.isResultListDeferred,
                        hasLocationPermission = input.location.hasLocationPermission,
                        hasRequestedLocationPermission = input.location.hasRequestedLocationPermission,
                        userLatitude = input.location.userLatitude,
                        userLongitude = input.location.userLongitude,
                        isLocating = input.location.isLocating,
                        selectedCanyon = resultSet.results.firstOrNull { it.id == input.selectedCanyonId },
                        scrollResetRequestId = input.scrollResetRequestId,
                    )
                }
                .flowOn(searchDispatcher)
                .catch { throwable ->
                    _uiState.value = SearchUiState(
                        queryDraft = queryDraftFlow.value,
                        criteria = filtersFlow.value.copy(
                            query = appliedQueryFlow.value,
                            userLatitude = locationFlow.value.userLatitude,
                            userLongitude = locationFlow.value.userLongitude,
                        ),
                        isLoading = false,
                        isSearching = false,
                        error = throwable.message ?: "Impossible de charger le catalogue local de recherche.",
                        resultViewMode = resultViewModeFlow.value,
                        hasLocationPermission = locationFlow.value.hasLocationPermission,
                        hasRequestedLocationPermission = locationFlow.value.hasRequestedLocationPermission,
                        userLatitude = locationFlow.value.userLatitude,
                        userLongitude = locationFlow.value.userLongitude,
                        isLocating = locationFlow.value.isLocating,
                        scrollResetRequestId = scrollResetRequestIdFlow.value,
                    )
                }
                .collect { state ->
                    _uiState.update { current -> state.copy(error = current.error ?: state.error) }
                }
        }
    }

    fun onQueryChanged(query: String) {
        val previousDraft = queryDraftFlow.value
        queryDraftFlow.value = query
        applyQuery(query = query, immediate = query.length <= previousDraft.length)
        selectedCanyonIdFlow.value = null
        _uiState.update {
            it.copy(queryDraft = query, isSearching = query != appliedQueryFlow.value)
        }
    }

    fun onCriteriaChanged(criteria: SearchCriteria) {
        val current = filtersFlow.value
        val sanitized = criteria.sanitizedForPersistence()
        val updated = if (!current.selectedCountry.equals(sanitized.selectedCountry, ignoreCase = true) && current.selectedDepartment == sanitized.selectedDepartment) {
            sanitized.copy(selectedDepartment = null)
        } else {
            sanitized
        }
        filtersFlow.value = updated.copy(query = "")
        scrollResetRequestIdFlow.update { it + 1 }
        selectedCanyonIdFlow.value = null
        persistCriteria(updated)
        _uiState.update {
            it.copy(
                criteria = updated.copy(
                    query = queryDraftFlow.value,
                    userLatitude = locationFlow.value.userLatitude,
                    userLongitude = locationFlow.value.userLongitude,
                ),
                activeFilterCount = updated.activeFilterCount(),
                isSearching = true,
            )
        }
    }

    fun onSortSelected(field: SearchSortField) {
        val current = filtersFlow.value
        val next = if (current.sortField == field) {
            current.copy(sortDirection = current.sortDirection.toggle())
        } else {
            current.copy(sortField = field, sortDirection = field.defaultDirection())
        }
        _uiState.update { it.copy(error = null) }
        onCriteriaChanged(next)
    }

    fun onResultViewModeChanged(mode: SearchResultViewMode) {
        resultViewModeFlow.value = mode
        if (mode == SearchResultViewMode.LIST) selectedCanyonIdFlow.value = null
    }

    fun onLocationPermissionResult(granted: Boolean) {
        locationFlow.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                isLocating = false,
            )
        }
        if (granted) {
            _uiState.update { it.copy(error = null) }
        }
    }

    fun onLocationLookupStarted() {
        locationFlow.update { it.copy(isLocating = true) }
    }

    fun onUserLocationUpdated(latitude: Double, longitude: Double) {
        locationFlow.update {
            it.copy(userLatitude = latitude, userLongitude = longitude, isLocating = false)
        }
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        locationFlow.update { it.copy(isLocating = false) }
    }

    fun selectCanyon(canyonId: Int) {
        selectedCanyonIdFlow.value = canyonId
    }

    fun clearSelectedCanyon() {
        selectedCanyonIdFlow.value = null
    }

    fun clearAllFilters() = onCriteriaChanged(filtersFlow.value.clearAllFilters())
    fun clearCountry() = onCriteriaChanged(filtersFlow.value.copy(selectedCountry = null, selectedDepartment = null))
    fun clearDepartment() = onCriteriaChanged(filtersFlow.value.copy(selectedDepartment = null))

    fun clearQuery() {
        queryDraftFlow.value = ""
        applyQuery(query = "", immediate = true)
        selectedCanyonIdFlow.value = null
        _uiState.update { it.copy(queryDraft = "", isSearching = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyQuery(query: String, immediate: Boolean) {
        queryDebounceJob?.cancel()
        if (immediate) {
            appliedQueryFlow.value = query
            return
        }
        queryDebounceJob = viewModelScope.launch {
            delay(200)
            appliedQueryFlow.value = query
        }
    }

    private fun loadCriteria(): SearchCriteria {
        val raw = savedStateHandle.get<String>(SEARCH_CRITERIA_KEY).orEmpty()
        return runCatching { json.decodeFromString<SearchCriteria>(raw) }.getOrDefault(SearchCriteria())
    }

    private fun persistCriteria(criteria: SearchCriteria) {
        savedStateHandle[SEARCH_CRITERIA_KEY] = json.encodeToString(criteria.sanitizedForPersistence())
    }

    private fun SortDirection.toggle(): SortDirection = if (this == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC

    private fun SearchSortField.defaultDirection(): SortDirection {
        return when (this) {
            SearchSortField.NAME, SearchSortField.DISTANCE -> SortDirection.ASC
            else -> SortDirection.DESC
        }
    }

    companion object {
        private const val SEARCH_CRITERIA_KEY = "search_criteria"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private data class SearchComputationInput(
    val catalog: List<CanyonSearchItem>,
    val criteria: SearchCriteria,
    val location: SearchLocationUiState,
    val resultViewMode: SearchResultViewMode,
    val selectedCanyonId: Int?,
    val scrollResetRequestId: Int,
)

private data class PartialSearchComputationInput(
    val catalog: List<CanyonSearchItem>,
    val criteria: SearchCriteria,
    val location: SearchLocationUiState,
    val resultViewMode: SearchResultViewMode,
    val scrollResetRequestId: Int = 0,
)

private fun SearchCriteria.sanitizedForPersistence(): SearchCriteria = copy(userLatitude = null, userLongitude = null)
