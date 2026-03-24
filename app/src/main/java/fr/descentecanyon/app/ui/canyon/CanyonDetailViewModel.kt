package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
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
    val error: String? = null,
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
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val downloadPhotoForOfflineUseCase: DownloadPhotoForOfflineUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"]
        ?: throw IllegalArgumentException("canyonId is required")

    private val _uiState = MutableStateFlow(CanyonDetailUiState())
    val uiState: StateFlow<CanyonDetailUiState> = _uiState.asStateFlow()

    init {
        loadCanyon(canyonId)
        observeFavorite(canyonId)
        observeConnectivity()
    }

    fun loadCanyon(id: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            getCanyonPreviewUseCase(id).onSuccess { preview ->
                _uiState.update {
                    it.copy(
                        canyonDetail = preview,
                        isLoading = false,
                        error = null,
                    )
                }
            }

            getCanyonDetailUseCase(id).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            canyonDetail = detail,
                            isLoading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message ?: "Erreur inconnue",
                        )
                    }
                },
            )
        }
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
}
