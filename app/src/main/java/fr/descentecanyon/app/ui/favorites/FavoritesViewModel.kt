package fr.descentecanyon.app.ui.favorites

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FavoriteSortOption {
    DATE_ADDED_DESC,
    DATE_ADDED_ASC,
    RATING_DESC,
    NAME_ASC,
}

data class FavoritesUiState(
    val rawFavorites: List<CanyonSummary> = emptyList(),
    val filteredFavorites: List<CanyonSummary> = emptyList(),
    val availableCountries: List<String> = emptyList(),
    val availableRegions: List<String> = emptyList(),
    val selectedCountry: String? = null,
    val selectedRegion: String? = null,
    val minRating: Float = 0f,
    val selectedSort: FavoriteSortOption = FavoriteSortOption.DATE_ADDED_DESC,
    val isLoading: Boolean = false,
    val error: String? = null,
)

private data class FilterParams(
    val sort: FavoriteSortOption = FavoriteSortOption.DATE_ADDED_DESC,
    val country: String? = null,
    val region: String? = null,
    val minRating: Float = 0f,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _rawFavorites = MutableStateFlow<List<CanyonSummary>>(emptyList())
    private val _filterParams = MutableStateFlow(FilterParams())
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FavoritesUiState> = combine(
        _rawFavorites,
        _filterParams,
        _isLoading,
        _error
    ) { rawList, params, isLoading, error ->
        val availableCountries = rawList.map { it.pays }.distinct().sorted()
        val availableRegions = rawList.mapNotNull { it.region ?: it.departement }.distinct().sorted()

        var filtered = rawList.asSequence()

        if (!params.country.isNullOrBlank()) {
            filtered = filtered.filter { it.pays.equals(params.country, ignoreCase = true) }
        }

        if (!params.region.isNullOrBlank()) {
            filtered = filtered.filter { 
                (it.region?.equals(params.region, ignoreCase = true) == true) ||
                (it.departement?.equals(params.region, ignoreCase = true) == true)
            }
        }

        if (params.minRating > 0f) {
            filtered = filtered.filter { (it.interet ?: 0f) >= params.minRating }
        }

        val sortedList = when (params.sort) {
            FavoriteSortOption.DATE_ADDED_DESC -> filtered.sortedByDescending { it.favoriteAddedAt ?: 0L }
            FavoriteSortOption.DATE_ADDED_ASC -> filtered.sortedBy { it.favoriteAddedAt ?: Long.MAX_VALUE }
            FavoriteSortOption.RATING_DESC -> filtered.sortedByDescending { it.interet ?: 0f }
            FavoriteSortOption.NAME_ASC -> filtered.sortedBy { it.nom }
        }.toList()

        FavoritesUiState(
            rawFavorites = rawList,
            filteredFavorites = sortedList,
            availableCountries = availableCountries,
            availableRegions = availableRegions,
            selectedCountry = params.country,
            selectedRegion = params.region,
            minRating = params.minRating,
            selectedSort = params.sort,
            isLoading = isLoading,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FavoritesUiState()
    )

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            _isLoading.value = true
            favoritesRepository.getFavorites().collect { favorites ->
                _rawFavorites.value = favorites
                _isLoading.value = false
                _error.value = null
            }
        }
    }

    fun setSortOption(option: FavoriteSortOption) {
        _filterParams.value = _filterParams.value.copy(sort = option)
    }

    fun setCountryFilter(country: String?) {
        _filterParams.value = _filterParams.value.copy(country = country)
    }

    fun setRegionFilter(region: String?) {
        _filterParams.value = _filterParams.value.copy(region = region)
    }

    fun setMinRatingFilter(minRating: Float) {
        _filterParams.value = _filterParams.value.copy(minRating = minRating)
    }

    fun resetFilters() {
        _filterParams.value = FilterParams()
    }

    fun removeFavorite(canyonId: Int) {
        viewModelScope.launch {
            runCatching {
                favoritesRepository.removeFavorite(canyonId)
            }.onFailure {
                _error.value = context.getString(R.string.favorite_remove_error)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
