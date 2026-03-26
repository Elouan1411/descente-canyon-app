package fr.descentecanyon.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.usecase.DownloadMapOfflineRegionUseCase
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.map.MAP_OFFLINE_RADIUS_KM
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val mapCanyons: List<CanyonSummary> = emptyList(),
    val selectedCanyon: CanyonSummary? = null,
    val isLoading: Boolean = true,
    val isDownloadingOfflineRegion: Boolean = false,
    val error: String? = null,
    val transientMessage: String? = null,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val cameraState: MapCameraState? = null,
    val focusLocationRequestId: Int = 0,
)

data class MapCameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    searchCanyonsUseCase: SearchCanyonsUseCase,
    private val downloadMapOfflineRegionUseCase: DownloadMapOfflineRegionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            searchCanyonsUseCase.observeCatalog().collect { catalog ->
                val mapCanyons = catalog.mapNotNull { item ->
                    item.takeIf { it.representativeLat != null && it.representativeLng != null }?.toSummary()
                }
                _uiState.update { state ->
                    state.copy(
                        mapCanyons = mapCanyons,
                        selectedCanyon = state.selectedCanyon?.let { selected ->
                            mapCanyons.firstOrNull { canyon -> canyon.id == selected.id }
                        },
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                error = null,
            )
        }
    }

    fun focusAroundUser(latitude: Double, longitude: Double) {
        _uiState.update {
            it.copy(
                error = null,
                transientMessage = null,
                userLatitude = latitude,
                userLongitude = longitude,
                focusLocationRequestId = it.focusLocationRequestId + 1,
            )
        }
    }

    fun onCameraChanged(cameraState: MapCameraState) {
        _uiState.update { state ->
            if (state.cameraState.isSameAs(cameraState)) {
                state
            } else {
                state.copy(cameraState = cameraState)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        _uiState.update {
            it.copy(transientMessage = "Aucune position recente disponible sur cet appareil.")
        }
    }

    fun downloadSelectedRegion(radiusKm: Double = MAP_OFFLINE_RADIUS_KM) {
        val selected = _uiState.value.selectedCanyon ?: return
        val latitude = selected.latitude ?: return
        val longitude = selected.longitude ?: return
        if (_uiState.value.isDownloadingOfflineRegion) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingOfflineRegion = true, transientMessage = null) }
            downloadMapOfflineRegionUseCase(
                name = selected.nom,
                latitude = latitude,
                longitude = longitude,
                radiusKm = radiusKm,
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isDownloadingOfflineRegion = false,
                            transientMessage = "Zone de carte telechargee pour ${selected.nom}",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isDownloadingOfflineRegion = false,
                            transientMessage = throwable.message ?: "Impossible de telecharger la zone hors-ligne.",
                        )
                    }
                },
            )
        }
    }

    fun selectCanyon(canyonId: Int) {
        _uiState.update { state ->
            state.copy(selectedCanyon = state.mapCanyons.firstOrNull { it.id == canyonId })
        }
    }

    fun clearSelectedCanyon() {
        _uiState.update { it.copy(selectedCanyon = null) }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }
}

private fun MapCameraState?.isSameAs(other: MapCameraState): Boolean {
    if (this == null) return false
    return abs(latitude - other.latitude) < 0.000001 &&
        abs(longitude - other.longitude) < 0.000001 &&
        abs(zoom - other.zoom) < 0.01
}
