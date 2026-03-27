package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CanyonDetailUiState(
    val canyonDetail: CanyonDetail? = null,
    val isLoading: Boolean = false,
    val isLoadingPhotos: Boolean = false,
    val isLoadingDebits: Boolean = false,
    val error: String? = null,
    val weather: CanyonWeather? = null,
    val isLoadingWeather: Boolean = false,
    val weatherError: String? = null,
    val isFavorite: Boolean = false,
    val downloadingPhotoIds: Set<Long> = emptySet(),
    val isOnline: Boolean = true,
    val transientMessage: String? = null,
)

@HiltViewModel
class CanyonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val getCanyonDetailUseCase: GetCanyonDetailUseCase,
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val photoRepository: PhotoRepository,
    private val debitRepository: DebitRepository,
    private val downloadPhotoForOfflineUseCase: DownloadPhotoForOfflineUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"]
        ?: throw IllegalArgumentException("canyonId is required")

    private val _uiState = MutableStateFlow(CanyonDetailUiState())
    val uiState: StateFlow<CanyonDetailUiState> = _uiState.asStateFlow()

    init {
        observePhotos(canyonId)
        observeDebits(canyonId)
        loadCanyon(canyonId)
        observeFavorite(canyonId)
        observeConnectivity()
    }

    fun loadCanyon(id: Int) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isLoadingPhotos = true,
                    isLoadingDebits = true,
                    isLoadingWeather = false,
                    error = null,
                    weather = null,
                    weatherError = null,
                )
            }

            val photosRefreshJob = viewModelScope.launch { refreshPhotos(id) }
            val debitsRefreshJob = viewModelScope.launch { refreshDebits(id) }

            getCanyonPreviewUseCase(id).onSuccess { preview ->
                _uiState.update {
                    it.copy(
                        canyonDetail = mergeBaseDetail(preview, it.canyonDetail),
                        isLoading = false,
                        error = null,
                    )
                }
            }

            getCanyonDetailUseCase(id).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            canyonDetail = mergeBaseDetail(detail, it.canyonDetail),
                            isLoading = false,
                            isLoadingWeather = true,
                            weather = null,
                            error = null,
                            weatherError = null,
                        )
                    }
                    loadWeather(detail)
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingPhotos = false,
                            isLoadingDebits = false,
                            isLoadingWeather = false,
                            error = throwable.message ?: "Erreur inconnue",
                        )
                    }
                },
            )
        }
    }

    private fun loadWeather(detail: CanyonDetail) {
        viewModelScope.launch {
            getCanyonWeatherUseCase(detail).fold(
                onSuccess = { weather ->
                    _uiState.update {
                        it.copy(
                            weather = weather,
                            isLoadingWeather = false,
                            weatherError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            weather = null,
                            isLoadingWeather = false,
                            weatherError = throwable.message ?: "Meteo indisponible",
                        )
                    }
                },
            )
        }
    }

    private fun observePhotos(id: Int) {
        viewModelScope.launch {
            photoRepository.observePhotos(id).collect { photos ->
                _uiState.update { state ->
                    state.copy(
                        canyonDetail = state.canyonDetail?.copy(photos = photos),
                    )
                }
            }
        }
    }

    private fun observeDebits(id: Int) {
        viewModelScope.launch {
            debitRepository.getDebitsForCanyon(id).collect { result ->
                result.onSuccess { debits ->
                    _uiState.update { state ->
                        state.copy(
                            canyonDetail = state.canyonDetail?.copy(debits = debits),
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshPhotos(id: Int) {
        photoRepository.refreshPhotos(id).fold(
            onSuccess = {
                _uiState.update { it.copy(isLoadingPhotos = false) }
            },
            onFailure = { throwable ->
                _uiState.update {
                    it.copy(
                        isLoadingPhotos = false,
                        transientMessage = throwable.message ?: "Impossible de charger les photos",
                    )
                }
            },
        )
    }

    private suspend fun refreshDebits(id: Int) {
        debitRepository.refreshDebits(id).fold(
            onSuccess = {
                _uiState.update { it.copy(isLoadingDebits = false) }
            },
            onFailure = { throwable ->
                _uiState.update {
                    it.copy(
                        isLoadingDebits = false,
                        transientMessage = throwable.message ?: "Impossible de charger les débits",
                    )
                }
            },
        )
    }

    private fun observeFavorite(id: Int) {
        viewModelScope.launch {
            favoritesRepository.isFavorite(id).collect { isFav ->
                _uiState.update { it.copy(isFavorite = isFav) }
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            toggleFavoriteUseCase(canyonId)
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun downloadPhoto(photoId: Long) {
        if (photoId == 0L || _uiState.value.downloadingPhotoIds.contains(photoId)) return

        viewModelScope.launch {
            _uiState.update { it.copy(downloadingPhotoIds = it.downloadingPhotoIds + photoId) }
            downloadPhotoForOfflineUseCase(photoId).fold(
                onSuccess = { localPath ->
                    _uiState.update { state ->
                        state.copy(
                            canyonDetail = state.canyonDetail?.copy(
                                photos = state.canyonDetail.photos.map { photo ->
                                    if (photo.id == photoId) photo.copy(localPath = localPath) else photo
                                }
                            ),
                            downloadingPhotoIds = state.downloadingPhotoIds - photoId,
                            transientMessage = "Photo telechargee",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            downloadingPhotoIds = it.downloadingPhotoIds - photoId,
                            transientMessage = throwable.message ?: "Impossible de telecharger la photo",
                        )
                    }
                },
            )
        }
    }

    private fun mergeBaseDetail(newDetail: CanyonDetail, currentDetail: CanyonDetail?): CanyonDetail {
        return if (currentDetail == null) {
            newDetail
        } else {
            newDetail.copy(
                photos = currentDetail.photos,
                debits = currentDetail.debits,
            )
        }
    }
}
