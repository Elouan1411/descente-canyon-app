package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val predictions: CanyonDebitPredictions? = null,
    val isLoadingPredictions: Boolean = false,
    val predictionError: String? = null,
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
    private val getCanyonDebitPredictionsUseCase: GetCanyonDebitPredictionsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val canyonRepository: CanyonRepository,
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
    private var predictionLoadJob: Job? = null

    init {
        observePhotos(canyonId)
        observeDebits(canyonId)
        observeWatershed(canyonId)
        loadCanyon(canyonId)
        observeFavorite(canyonId)
        observeConnectivity()
    }

    fun loadCanyon(id: Int) {
        viewModelScope.launch {
            predictionLoadJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isLoadingPhotos = true,
                    isLoadingDebits = true,
                    isLoadingWeather = true,
                    isLoadingPredictions = true,
                    error = null,
                    weather = null,
                    weatherError = null,
                    predictions = null,
                    predictionError = null,
                )
            }

            val photosRefreshJob = viewModelScope.launch { refreshPhotos(id) }
            val debitsRefreshJob = viewModelScope.launch { refreshDebits(id) }

            launch {
                getCanyonPreviewUseCase(id).onSuccess { preview ->
                    _uiState.update { state ->
                        if (state.canyonDetail != null) {
                            state
                        } else {
                            state.copy(
                                canyonDetail = mergeBaseDetail(preview, state.canyonDetail),
                                isLoading = false,
                                error = null,
                            )
                        }
                    }
                }
            }

            launch {
                getCanyonDetailUseCase(id).fold(
                    onSuccess = { detail ->
                        _uiState.update {
                            it.copy(
                                canyonDetail = mergeBaseDetail(detail, it.canyonDetail),
                                isLoading = false,
                                isLoadingWeather = true,
                                isLoadingPredictions = true,
                                weather = null,
                                predictions = null,
                                error = null,
                                weatherError = null,
                                predictionError = null,
                            )
                        }
                        loadWeather(detail)
                        schedulePredictions(detail)
                    },
                    onFailure = { throwable ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingPhotos = false,
                                isLoadingDebits = false,
                                isLoadingWeather = false,
                                isLoadingPredictions = false,
                                error = throwable.toFriendlyCanyonDetailMessage(),
                            )
                        }
                    },
                )
            }
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
                            weatherError = throwable.toFriendlyWeatherMessage(),
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

    private fun observeWatershed(id: Int) {
        viewModelScope.launch {
            canyonRepository.observeWatershed(id).collect { watershed ->
                _uiState.update { state ->
                    state.copy(
                        canyonDetail = state.canyonDetail?.copy(watershed = watershed),
                    )
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
                        transientMessage = throwable.toFriendlyDebitsMessage(),
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
                            transientMessage = "Photo téléchargée",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            downloadingPhotoIds = it.downloadingPhotoIds - photoId,
                            transientMessage = throwable.toFriendlyPhotoMessage(),
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

    private suspend fun loadPredictions(detail: CanyonDetail) {
        getCanyonDebitPredictionsUseCase(detail).fold(
            onSuccess = { predictions ->
                _uiState.update {
                    it.copy(
                        predictions = predictions,
                        isLoadingPredictions = false,
                        predictionError = null,
                    )
                }
            },
            onFailure = { throwable ->
                _uiState.update {
                    it.copy(
                        predictions = null,
                        isLoadingPredictions = false,
                        predictionError = throwable.toFriendlyPredictionMessage(),
                    )
                }
            },
        )
    }

    private fun schedulePredictions(detail: CanyonDetail) {
        predictionLoadJob?.cancel()
        predictionLoadJob = viewModelScope.launch {
            // Let the canyon screen settle before loading the heaviest remote/native prediction stack.
            delay(PREDICTION_LOAD_DELAY_MS)
            loadPredictions(detail)
        }
    }

    private fun Throwable.toFriendlyCanyonDetailMessage(): String {
        return "Impossible de charger cette fiche canyon pour le moment."
    }

    private fun Throwable.toFriendlyWeatherMessage(): String {
        return if (isLikelyNetworkIssue()) {
            "Impossible de récupérer la météo pour le moment."
        } else {
            "Météo indisponible pour le moment."
        }
    }

    private fun Throwable.toFriendlyPredictionMessage(): String {
        return if (isLikelyNetworkIssue()) {
            "Impossible de calculer l'estimation du débit pour le moment."
        } else {
            "Estimation du débit indisponible pour le moment."
        }
    }

    private fun Throwable.toFriendlyDebitsMessage(): String {
        return if (isLikelyNetworkIssue()) {
            "Impossible de charger les débits pour le moment."
        } else {
            "Débits indisponibles pour le moment."
        }
    }

    private fun Throwable.toFriendlyPhotoMessage(): String {
        return "Impossible de charger la photo pour le moment."
    }

    private fun Throwable.isLikelyNetworkIssue(): Boolean {
        return generateSequence(this) { it.cause }.any { cause ->
            cause is UnknownHostException ||
                cause is UnresolvedAddressException ||
                cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause.message?.contains("Read timed out", ignoreCase = true) == true ||
                cause.message?.contains("Read time out", ignoreCase = true) == true ||
                cause.message?.contains("timeout", ignoreCase = true) == true ||
                cause.message?.contains("timed out", ignoreCase = true) == true ||
                cause.message?.contains("Unable to resolve host", ignoreCase = true) == true
        }
    }

    private companion object {
        const val PREDICTION_LOAD_DELAY_MS = 800L
    }
}
