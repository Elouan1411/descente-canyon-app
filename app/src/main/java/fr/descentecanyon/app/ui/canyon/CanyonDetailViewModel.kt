package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.usecase.DownloadCanyonOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
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
    val isDownloading: Boolean = false,
    val transientMessage: String? = null,
)

@HiltViewModel
class CanyonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonDetailUseCase: GetCanyonDetailUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val downloadCanyonOfflineUseCase: DownloadCanyonOfflineUseCase,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"]
        ?: throw IllegalArgumentException("canyonId is required")

    private val _uiState = MutableStateFlow(CanyonDetailUiState())
    val uiState: StateFlow<CanyonDetailUiState> = _uiState.asStateFlow()

    init {
        loadCanyon(canyonId)
        observeFavorite(canyonId)
    }

    fun loadCanyon(id: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
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

    fun toggleFavorite() {
        viewModelScope.launch {
            toggleFavoriteUseCase(canyonId)
        }
    }

    fun downloadForOffline() {
        if (_uiState.value.isDownloading || _uiState.value.canyonDetail?.canyon?.isOffline == true) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, transientMessage = null) }
            downloadCanyonOfflineUseCase(canyonId).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            canyonDetail = state.canyonDetail?.let { detail ->
                                detail.copy(canyon = detail.canyon.copy(isOffline = true))
                            },
                            isDownloading = false,
                            transientMessage = "Disponible hors-ligne",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            transientMessage = throwable.message ?: "Telechargement impossible",
                        )
                    }
                },
            )
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }
}
