package fr.descentecanyon.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.usecase.GetNearbyCanyonsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val canyons: List<CanyonSummary> = emptyList(),
    val selectedCanyon: CanyonSummary? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val getNearbyCanyonsUseCase: GetNearbyCanyonsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var nearbyJob: Job? = null

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                error = if (!granted) "La position est necessaire pour charger les canyons proches." else null,
            )
        }
    }

    fun loadNearby(latitude: Double, longitude: Double, radiusKm: Double = 50.0) {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    userLatitude = latitude,
                    userLongitude = longitude,
                )
            }

            getNearbyCanyonsUseCase(latitude, longitude, radiusKm).collect { result ->
                result.fold(
                    onSuccess = { canyons ->
                        _uiState.update {
                            it.copy(
                                canyons = canyons,
                                selectedCanyon = it.selectedCanyon?.let { selected ->
                                    canyons.firstOrNull { canyon -> canyon.id == selected.id }
                                },
                                isLoading = false,
                                error = null,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = throwable.message ?: "Impossible de charger les canyons proches.",
                            )
                        }
                    },
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        _uiState.update {
            it.copy(error = "Aucune position recente disponible sur cet appareil.")
        }
    }

    fun selectCanyon(canyonId: Int) {
        _uiState.update { state ->
            state.copy(selectedCanyon = state.canyons.firstOrNull { it.id == canyonId })
        }
    }

    fun clearSelectedCanyon() {
        _uiState.update { it.copy(selectedCanyon = null) }
    }
}
