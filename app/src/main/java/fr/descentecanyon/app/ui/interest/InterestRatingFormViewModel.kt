package fr.descentecanyon.app.ui.interest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.InterestRatingSessionRequiredException
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.usecase.GetCanyonInterestRatingUseCase
import fr.descentecanyon.app.domain.usecase.SubmitCanyonInterestRatingUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class InterestRatingFormUiState(
    val canyonId: Int = 0,
    val isConnected: Boolean = false,
    val username: String = "",
    val ratingTenths: Int = 20,
    val personalRating: Float? = null,
    val averageRating: Float? = null,
    val medianRating: Float? = null,
    val voteCount: Int? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val transientMessage: String? = null,
    val submitted: Boolean = false,
    val loginRequiredMessage: String? = null,
)

@HiltViewModel
class InterestRatingFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val getCanyonInterestRatingUseCase: GetCanyonInterestRatingUseCase,
    private val submitCanyonInterestRatingUseCase: SubmitCanyonInterestRatingUseCase,
) : ViewModel() {

    private val canyonId: Int = checkNotNull(savedStateHandle["canyonId"])
    private val _uiState = MutableStateFlow(InterestRatingFormUiState(canyonId = canyonId))
    val uiState: StateFlow<InterestRatingFormUiState> = _uiState.asStateFlow()
    private var loadedForCurrentSession = false

    init {
        observeAuth()
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                when (authState) {
                    is AuthState.Connected -> {
                        _uiState.update {
                            it.copy(
                                isConnected = true,
                                username = authState.username,
                                error = null,
                            )
                        }
                        if (!loadedForCurrentSession) {
                            loadedForCurrentSession = true
                            loadRating()
                        }
                    }
                    else -> {
                        loadedForCurrentSession = false
                        _uiState.update { it.copy(isConnected = false, username = "") }
                    }
                }
            }
        }
    }

    fun loadRating() {
        if (!_uiState.value.isConnected) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            getCanyonInterestRatingUseCase(canyonId).fold(
                onSuccess = { rating ->
                    val personalTenths = rating.personalRating?.toTenths()
                    _uiState.update {
                        it.copy(
                            ratingTenths = personalTenths ?: it.ratingTenths,
                            personalRating = rating.personalRating,
                            averageRating = rating.averageRating,
                            medianRating = rating.medianRating,
                            voteCount = rating.voteCount,
                            isLoading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    handleFailure(throwable, defaultMessage = "Impossible de charger votre note.", isLoading = false)
                },
            )
        }
    }

    fun onRatingTenthsChanged(value: Int) {
        _uiState.update { it.copy(ratingTenths = value.coerceIn(0, 40), error = null) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.isConnected) {
            _uiState.update {
                it.copy(loginRequiredMessage = "Connecte-toi à ton compte Descente-Canyon avant de noter ce canyon.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, transientMessage = null) }
            submitCanyonInterestRatingUseCase(
                InterestRatingSubmission(
                    canyonId = canyonId,
                    rating = state.ratingTenths / 10f,
                )
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            personalRating = state.ratingTenths / 10f,
                            transientMessage = "Note enregistrée",
                            submitted = true,
                        )
                    }
                },
                onFailure = { throwable ->
                    handleFailure(throwable, defaultMessage = "Impossible d'enregistrer la note.", isSubmitting = false)
                },
            )
        }
    }

    private suspend fun handleFailure(
        throwable: Throwable,
        defaultMessage: String,
        isLoading: Boolean = _uiState.value.isLoading,
        isSubmitting: Boolean = _uiState.value.isSubmitting,
    ) {
        val requiresLogin = throwable is InterestRatingSessionRequiredException
        if (requiresLogin) {
            authRepository.logout()
        }
        _uiState.update {
            it.copy(
                isLoading = isLoading,
                isSubmitting = isSubmitting,
                error = throwable.message ?: defaultMessage,
                loginRequiredMessage = if (requiresLogin) throwable.message else null,
            )
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun clearSubmitted() {
        _uiState.update { it.copy(submitted = false) }
    }

    fun clearLoginRequiredMessage() {
        _uiState.update { it.copy(loginRequiredMessage = null) }
    }
}

private fun Float.toTenths(): Int = (this * 10f).roundToInt().coerceIn(0, 40)
