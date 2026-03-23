package fr.descentecanyon.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchFilter {
    ALL,
    OFFLINE,
}

data class SearchUiState(
    val query: String = "",
    val results: List<CanyonSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedFilter: SearchFilter = SearchFilter.ALL,
    val availableCountries: List<String> = emptyList(),
    val selectedCountry: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchCanyonsUseCase: SearchCanyonsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var latestResults: List<CanyonSummary> = emptyList()

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300L)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.length < 2) {
                        latestResults = emptyList()
                        flowOf(Result.success(emptyList()))
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        searchCanyonsUseCase(query)
                    }
                }
                .collect { result ->
                    result.fold(
                        onSuccess = { canyons ->
                            latestResults = canyons
                            _uiState.update {
                                it.copy(
                                    results = filterResults(canyons, it.selectedFilter, it.selectedCountry),
                                    isLoading = false,
                                    error = null,
                                    availableCountries = availableCountries(canyons),
                                )
                            }
                        },
                        onFailure = { throwable ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = throwable.message,
                                )
                            }
                        },
                    )
                }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                query = query,
                results = if (query.length < 2) emptyList() else it.results,
                error = null,
            )
        }
        queryFlow.value = query
    }

    fun onFilterSelected(filter: SearchFilter) {
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                results = filterResults(latestResults, filter, it.selectedCountry),
            )
        }
    }

    fun onCountrySelected(country: String?) {
        _uiState.update {
            it.copy(
                selectedCountry = country,
                results = filterResults(latestResults, it.selectedFilter, country),
            )
        }
    }

    private fun filterResults(
        results: List<CanyonSummary>,
        filter: SearchFilter,
        country: String?,
    ): List<CanyonSummary> {
        val filtered = when (filter) {
            SearchFilter.ALL -> results
            SearchFilter.OFFLINE -> results.filter { it.isOffline }
        }

        return country?.let { selected ->
            filtered.filter { it.pays.equals(selected, ignoreCase = true) }
        } ?: filtered
    }

    private fun availableCountries(results: List<CanyonSummary>): List<String> {
        return results.mapNotNull { it.pays.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }
}
