package fr.descentecanyon.app.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val mapCanyons: List<CanyonSummary> = emptyList(),
    val selectedCanyon: CanyonSummary? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val transientMessage: String? = null,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val isLocating: Boolean = false,
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
    private val savedStateHandle: SavedStateHandle,
    searchCanyonsUseCase: SearchCanyonsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MapUiState(
            hasLocationPermission = savedStateHandle[MAP_HAS_LOCATION_PERMISSION_KEY] ?: false,
            hasRequestedLocationPermission = savedStateHandle[MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY] ?: false,
            userLatitude = savedStateHandle.get<Double>(MAP_USER_LATITUDE_KEY),
            userLongitude = savedStateHandle.get<Double>(MAP_USER_LONGITUDE_KEY),
            cameraState = loadCameraState(savedStateHandle),
            focusLocationRequestId = savedStateHandle[MAP_FOCUS_LOCATION_REQUEST_ID_KEY] ?: 0,
        )
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    private var hasLoggedInitialCatalog = false
    private val restoredSelectedCanyonId = savedStateHandle.get<Int>(MAP_SELECTED_CANYON_ID_KEY)

    init {
        PerformanceTrace.start(MAP_INITIAL_TRACE_KEY, "map_initial_load")
        viewModelScope.launch {
            searchCanyonsUseCase.observeCatalog()
                .catch { throwable ->
                    if (!hasLoggedInitialCatalog) {
                        hasLoggedInitialCatalog = true
                        PerformanceTrace.end(
                            key = MAP_INITIAL_TRACE_KEY,
                            outcome = "failed",
                            "error" to (throwable.message ?: throwable::class.simpleName),
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message ?: "Impossible de charger la carte.",
                        )
                    }
                }
                .collect { catalog ->
                val mappingStartedAt = monotonicNowMs()
                val mapCanyons = catalog.mapNotNull { item ->
                    item.takeIf { it.representativeLat != null && it.representativeLng != null }?.toSummary()
                }
                PerformanceTrace.logEvent(
                    event = "map_catalog_compute",
                    "catalogSize" to catalog.size,
                    "markerCount" to mapCanyons.size,
                    "durationMs" to (monotonicNowMs() - mappingStartedAt),
                )
                _uiState.update { state ->
                    state.copy(
                        mapCanyons = mapCanyons,
                        selectedCanyon = (state.selectedCanyon?.id ?: restoredSelectedCanyonId)?.let { selectedId ->
                            mapCanyons.firstOrNull { canyon -> canyon.id == selectedId }
                        },
                        isLoading = false,
                    )
                }
                if (!hasLoggedInitialCatalog) {
                    hasLoggedInitialCatalog = true
                    PerformanceTrace.end(
                        key = MAP_INITIAL_TRACE_KEY,
                        outcome = "ready",
                        "catalogSize" to catalog.size,
                        "markerCount" to mapCanyons.size,
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
        savedStateHandle[MAP_HAS_LOCATION_PERMISSION_KEY] = granted
        savedStateHandle[MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY] = true
    }

    fun onLocationLookupStarted() {
        _uiState.update { it.copy(isLocating = true, error = null, transientMessage = null) }
    }

    fun focusAroundUser(latitude: Double, longitude: Double) {
        _uiState.update {
            it.copy(
                error = null,
                transientMessage = null,
                isLocating = false,
                userLatitude = latitude,
                userLongitude = longitude,
                focusLocationRequestId = it.focusLocationRequestId + 1,
            )
        }
        savedStateHandle[MAP_USER_LATITUDE_KEY] = latitude
        savedStateHandle[MAP_USER_LONGITUDE_KEY] = longitude
        savedStateHandle[MAP_FOCUS_LOCATION_REQUEST_ID_KEY] = _uiState.value.focusLocationRequestId
    }

    fun onCameraChanged(cameraState: MapCameraState) {
        _uiState.update { state ->
            if (state.cameraState.isSameAs(cameraState)) {
                state
            } else {
                state.copy(cameraState = cameraState)
            }
        }
        persistCameraState(cameraState)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        _uiState.update {
            it.copy(
                isLocating = false,
                transientMessage = "Impossible d'obtenir une position récente. Vérifiez que la localisation est activée.",
            )
        }
    }

    fun selectCanyon(canyonId: Int) {
        _uiState.update { state ->
            state.copy(selectedCanyon = state.mapCanyons.firstOrNull { it.id == canyonId })
        }
        savedStateHandle[MAP_SELECTED_CANYON_ID_KEY] = canyonId
    }

    fun clearSelectedCanyon() {
        _uiState.update { it.copy(selectedCanyon = null) }
        savedStateHandle[MAP_SELECTED_CANYON_ID_KEY] = null
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun persistCameraState(cameraState: MapCameraState) {
        savedStateHandle[MAP_CAMERA_LATITUDE_KEY] = cameraState.latitude
        savedStateHandle[MAP_CAMERA_LONGITUDE_KEY] = cameraState.longitude
        savedStateHandle[MAP_CAMERA_ZOOM_KEY] = cameraState.zoom
    }
}

private const val MAP_INITIAL_TRACE_KEY = "screen.map.initial"
private const val MAP_HAS_LOCATION_PERMISSION_KEY = "map_has_location_permission"
private const val MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY = "map_has_requested_location_permission"
private const val MAP_USER_LATITUDE_KEY = "map_user_latitude"
private const val MAP_USER_LONGITUDE_KEY = "map_user_longitude"
private const val MAP_CAMERA_LATITUDE_KEY = "map_camera_latitude"
private const val MAP_CAMERA_LONGITUDE_KEY = "map_camera_longitude"
private const val MAP_CAMERA_ZOOM_KEY = "map_camera_zoom"
private const val MAP_SELECTED_CANYON_ID_KEY = "map_selected_canyon_id"
private const val MAP_FOCUS_LOCATION_REQUEST_ID_KEY = "map_focus_location_request_id"

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000

private fun loadCameraState(savedStateHandle: SavedStateHandle): MapCameraState? {
    val latitude = savedStateHandle.get<Double>(MAP_CAMERA_LATITUDE_KEY) ?: return null
    val longitude = savedStateHandle.get<Double>(MAP_CAMERA_LONGITUDE_KEY) ?: return null
    val zoom = savedStateHandle.get<Double>(MAP_CAMERA_ZOOM_KEY) ?: return null
    return MapCameraState(latitude = latitude, longitude = longitude, zoom = zoom)
}

private fun MapCameraState?.isSameAs(other: MapCameraState): Boolean {
    if (this == null) return false
    return abs(latitude - other.latitude) < 0.000001 &&
        abs(longitude - other.longitude) < 0.000001 &&
        abs(zoom - other.zoom) < 0.01
}
