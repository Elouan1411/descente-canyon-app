package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonEdfPracticabilityUseCase
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
    val isRefreshingDetail: Boolean = false,
    val isLoadingPhotos: Boolean = false,
    val photoError: String? = null,
    val isLoadingDebits: Boolean = false,
    val debitError: String? = null,
    val error: String? = null,
    val weather: CanyonWeather? = null,
    val isLoadingWeather: Boolean = false,
    val weatherError: String? = null,
    val edfStatus: CanyonEdfPracticability? = null,
    val isLoadingEdfStatus: Boolean = false,
    val edfStatusError: String? = null,
    val edfStatusSourceUrl: String? = null,
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
    private val getCanyonEdfPracticabilityUseCase: GetCanyonEdfPracticabilityUseCase,
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
    private var hasLoggedPrimaryContentReady = false

    init {
        _uiState.update {
            it.copy(
                edfStatusSourceUrl = getCanyonEdfPracticabilityUseCase.getReference(canyonId)?.sourceUrl,
            )
        }
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
            hasLoggedPrimaryContentReady = false
            val hasExistingContent = _uiState.value.canyonDetail != null
            PerformanceTrace.start(detailLoadKey(id), "canyon_detail_load", "canyonId" to id)
            _uiState.update {
                it.copy(
                    isLoading = !hasExistingContent,
                    isRefreshingDetail = hasExistingContent,
                    isLoadingPhotos = true,
                    photoError = null,
                    isLoadingDebits = true,
                    debitError = null,
                    isLoadingWeather = false,
                    isLoadingPredictions = false,
                    error = null,
                )
            }

            val photosRefreshJob = viewModelScope.launch { refreshPhotos(id) }
            val debitsRefreshJob = viewModelScope.launch { refreshDebits(id) }

            launch {
                getCanyonPreviewUseCase(id).onSuccess { preview ->
                    val wasEmpty = _uiState.value.canyonDetail == null
                    _uiState.update { state ->
                        if (state.canyonDetail != null) {
                            state
                        } else {
                            state.copy(
                                canyonDetail = mergeBaseDetail(preview, state.canyonDetail),
                                isLoading = false,
                                isRefreshingDetail = true,
                                error = null,
                            )
                        }
                    }
                    loadDeferredSectionsIfNeeded(preview)
                    if (wasEmpty && !hasLoggedPrimaryContentReady) {
                        hasLoggedPrimaryContentReady = true
                        PerformanceTrace.end(
                            key = detailLoadKey(id),
                            outcome = "preview_ready",
                            "canyonId" to id,
                        )
                    }
                }
            }

            launch {
                val detailLoadStartedAt = monotonicNowMs()
                getCanyonDetailUseCase(id).fold(
                    onSuccess = { detail ->
                        _uiState.update {
                            it.copy(
                                canyonDetail = mergeBaseDetail(detail, it.canyonDetail),
                                isLoading = false,
                                isRefreshingDetail = false,
                                error = null,
                            )
                        }
                        PerformanceTrace.logEvent(
                            event = "canyon_detail_full_ready",
                            "canyonId" to id,
                            "photoCount" to detail.photos.size,
                            "debitCount" to detail.debits.size,
                            "durationMs" to (monotonicNowMs() - detailLoadStartedAt),
                        )
                        if (!hasLoggedPrimaryContentReady) {
                            hasLoggedPrimaryContentReady = true
                            PerformanceTrace.end(
                                key = detailLoadKey(id),
                                outcome = "detail_ready",
                                "canyonId" to id,
                            )
                        }
                        loadDeferredSectionsIfNeeded(detail)
                    },
                    onFailure = { throwable ->
                        val hasContent = _uiState.value.canyonDetail != null
                        if (!hasLoggedPrimaryContentReady) {
                            PerformanceTrace.end(
                                key = detailLoadKey(id),
                                outcome = "failed",
                                "canyonId" to id,
                                "error" to (throwable.message ?: throwable::class.simpleName),
                            )
                        }
                        _uiState.update { state ->
                            if (hasContent) {
                                state.copy(
                                    isLoading = false,
                                    isRefreshingDetail = false,
                                    error = null,
                                    transientMessage = throwable.toFriendlyCanyonDetailMessage(),
                                )
                            } else {
                                state.copy(
                                    isLoading = false,
                                    isRefreshingDetail = false,
                                    isLoadingPhotos = false,
                                    photoError = null,
                                    isLoadingDebits = false,
                                    debitError = null,
                                    isLoadingWeather = false,
                                    isLoadingPredictions = false,
                                    error = throwable.toFriendlyCanyonDetailMessage(),
                                )
                            }
                        }
                        _uiState.value.canyonDetail?.let(::loadDeferredSectionsIfNeeded)
                    },
                )
            }
        }
    }

    private fun loadDeferredSectionsIfNeeded(detail: CanyonDetail) {
        maybeLoadEdfStatus()
        maybeLoadWeather(detail)
        maybeSchedulePredictions(detail)
    }

    private fun maybeLoadEdfStatus() {
        val state = _uiState.value
        val sourceUrl = state.edfStatusSourceUrl ?: return
        val shouldLoad = state.edfStatus == null || state.edfStatusError != null
        if (!shouldLoad || state.isLoadingEdfStatus) return

        _uiState.update {
            it.copy(
                isLoadingEdfStatus = true,
                edfStatusError = null,
                edfStatusSourceUrl = sourceUrl,
            )
        }
        loadEdfStatus(canyonId)
    }

    private fun maybeLoadWeather(detail: CanyonDetail) {
        val state = _uiState.value
        val shouldLoad = state.weather == null || state.weatherError != null
        if (!shouldLoad || state.isLoadingWeather) return

        _uiState.update {
            it.copy(
                isLoadingWeather = true,
                weatherError = null,
            )
        }
        loadWeather(detail)
    }

    private fun loadEdfStatus(canyonId: Int) {
        viewModelScope.launch {
            PerformanceTrace.start(edfStatusLoadKey(canyonId), "canyon_edf_status_load", "canyonId" to canyonId)
            getCanyonEdfPracticabilityUseCase(canyonId).fold(
                onSuccess = { status ->
                    PerformanceTrace.end(
                        key = edfStatusLoadKey(canyonId),
                        outcome = "ready",
                        "canyonId" to canyonId,
                        "state" to status.state.name,
                    )
                    _uiState.update {
                        it.copy(
                            edfStatus = status,
                            isLoadingEdfStatus = false,
                            edfStatusError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    PerformanceTrace.end(
                        key = edfStatusLoadKey(canyonId),
                        outcome = "failed",
                        "canyonId" to canyonId,
                        "error" to (throwable.message ?: throwable::class.simpleName),
                    )
                    _uiState.update {
                        it.copy(
                            edfStatus = null,
                            isLoadingEdfStatus = false,
                            edfStatusError = throwable.toFriendlyEdfStatusMessage(),
                        )
                    }
                },
            )
        }
    }

    private fun maybeSchedulePredictions(detail: CanyonDetail) {
        val state = _uiState.value
        val shouldLoad = state.predictions == null || state.predictionError != null
        if (!shouldLoad || state.isLoadingPredictions) return

        _uiState.update {
            it.copy(
                isLoadingPredictions = true,
                predictionError = null,
            )
        }
        schedulePredictions(detail)
    }

    private fun loadWeather(detail: CanyonDetail) {
        viewModelScope.launch {
            PerformanceTrace.start(weatherLoadKey(detail.canyon.id), "canyon_weather_load", "canyonId" to detail.canyon.id)
            getCanyonWeatherUseCase(detail).fold(
                onSuccess = { weather ->
                    PerformanceTrace.end(
                        key = weatherLoadKey(detail.canyon.id),
                        outcome = "ready",
                        "canyonId" to detail.canyon.id,
                        "hourlyCount" to weather.hourly.size,
                    )
                    _uiState.update {
                        it.copy(
                            weather = weather,
                            isLoadingWeather = false,
                            weatherError = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    PerformanceTrace.end(
                        key = weatherLoadKey(detail.canyon.id),
                        outcome = "failed",
                        "canyonId" to detail.canyon.id,
                        "error" to (throwable.message ?: throwable::class.simpleName),
                    )
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
        PerformanceTrace.start(photoRefreshKey(id), "canyon_photos_refresh", "canyonId" to id)
        photoRepository.refreshPhotos(id).fold(
            onSuccess = {
                PerformanceTrace.end(photoRefreshKey(id), outcome = "ready", "canyonId" to id)
                _uiState.update { it.copy(isLoadingPhotos = false, photoError = null) }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(
                    key = photoRefreshKey(id),
                    outcome = "failed",
                    "canyonId" to id,
                    "error" to (throwable.message ?: throwable::class.simpleName),
                )
                _uiState.update {
                    it.copy(
                        isLoadingPhotos = false,
                        photoError = throwable.toFriendlyPhotosMessage(),
                    )
                }
            },
        )
    }

    private suspend fun refreshDebits(id: Int) {
        PerformanceTrace.start(debitRefreshKey(id), "canyon_debits_refresh", "canyonId" to id)
        debitRepository.refreshDebits(id).fold(
            onSuccess = {
                PerformanceTrace.end(debitRefreshKey(id), outcome = "ready", "canyonId" to id)
                _uiState.update { it.copy(isLoadingDebits = false, debitError = null) }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(
                    key = debitRefreshKey(id),
                    outcome = "failed",
                    "canyonId" to id,
                    "error" to (throwable.message ?: throwable::class.simpleName),
                )
                _uiState.update {
                    it.copy(
                        isLoadingDebits = false,
                        debitError = throwable.toFriendlyDebitsMessage(),
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
        return mergeBaseCanyonDetail(newDetail, currentDetail)
    }

    private suspend fun loadPredictions(detail: CanyonDetail) {
        PerformanceTrace.start(predictionLoadKey(detail.canyon.id), "canyon_predictions_load", "canyonId" to detail.canyon.id)
        getCanyonDebitPredictionsUseCase(detail).fold(
            onSuccess = { predictions ->
                PerformanceTrace.end(
                    key = predictionLoadKey(detail.canyon.id),
                    outcome = "ready",
                    "canyonId" to detail.canyon.id,
                    "dayCount" to predictions.predictions.size,
                )
                _uiState.update {
                    it.copy(
                        predictions = predictions,
                        isLoadingPredictions = false,
                        predictionError = null,
                    )
                }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(
                    key = predictionLoadKey(detail.canyon.id),
                    outcome = "failed",
                    "canyonId" to detail.canyon.id,
                    "error" to (throwable.message ?: throwable::class.simpleName),
                )
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

    private fun Throwable.toFriendlyEdfStatusMessage(): String {
        return if (isLikelyNetworkIssue()) {
            "Impossible de récupérer les conditions EDF pour le moment."
        } else {
            "Conditions EDF indisponibles pour le moment."
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

    private fun Throwable.toFriendlyPhotosMessage(): String {
        return if (isLikelyNetworkIssue()) {
            "Impossible de charger les photos pour le moment."
        } else {
            "Photos indisponibles pour le moment."
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

internal fun mergeBaseCanyonDetail(newDetail: CanyonDetail, currentDetail: CanyonDetail?): CanyonDetail {
    return if (currentDetail == null) {
        newDetail
    } else {
        newDetail.copy(
            photos = currentDetail.photos,
            debits = currentDetail.debits,
            watershed = newDetail.watershed ?: currentDetail.watershed,
        )
    }
}

private fun detailLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.detail"

private fun weatherLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.weather"

private fun edfStatusLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.edf"

private fun debitRefreshKey(canyonId: Int): String = "screen.canyon.$canyonId.debits"

private fun photoRefreshKey(canyonId: Int): String = "screen.canyon.$canyonId.photos"

private fun predictionLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.predictions"

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000
