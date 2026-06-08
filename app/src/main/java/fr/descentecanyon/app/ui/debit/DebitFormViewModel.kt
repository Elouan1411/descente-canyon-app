package fr.descentecanyon.app.ui.debit

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionSessionExpiredException
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.usecase.SubmitDebitUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DebitFormUiState(
    val canyonId: Int = 0,
    val observerName: String = "",
    val observerEmail: String = "",
    val observationDate: LocalDate = LocalDate.now(),
    val observationType: ObservationType? = null,
    val debitLevel: NiveauDebit? = null,
    val waterTemperature: WaterTemperature? = null,
    val airTemperature: AirTemperature? = null,
    val comment: String = "",
    val personalComment: String = "",
    val isConnected: Boolean = false,
    val pendingCount: Int = 0,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val transientMessage: String? = null,
    val lastSubmissionStatus: DebitSubmissionStatus? = null,
    val loginRequiredMessage: String? = null,
)

@HiltViewModel
class DebitFormViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val submitDebitUseCase: SubmitDebitUseCase,
    private val debitSubmissionRepository: DebitSubmissionRepository,
) : ViewModel() {

    private val canyonId: Int = checkNotNull(savedStateHandle["canyonId"])

    private val _uiState = MutableStateFlow(DebitFormUiState(canyonId = canyonId))
    val uiState: StateFlow<DebitFormUiState> = _uiState.asStateFlow()

    init {
        observeAuth()
        observePendingCount()
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update { state ->
                    when (authState) {
                        is AuthState.Connected -> state.copy(
                            isConnected = true,
                            observerName = authState.username,
                            error = null,
                        )

                        else -> state.copy(isConnected = false)
                    }
                }
            }
        }
    }

    private fun observePendingCount() {
        viewModelScope.launch {
            debitSubmissionRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingCount = count) }
            }
        }
    }

    fun onObserverNameChanged(value: String) {
        _uiState.update { it.copy(observerName = value, error = null) }
    }

    fun onObserverEmailChanged(value: String) {
        _uiState.update { it.copy(observerEmail = value, error = null) }
    }

    fun onObservationDateChanged(value: String) {
        runCatching { LocalDate.parse(value) }.onSuccess { parsed ->
            onObservationDateSelected(parsed)
        }
    }

    fun onObservationDateSelected(value: LocalDate) {
        _uiState.update { it.copy(observationDate = value, error = null) }
    }

    fun onObservationTypeChanged(value: ObservationType) {
        _uiState.update { it.copy(observationType = value, error = null) }
    }

    fun onDebitLevelChanged(value: NiveauDebit) {
        _uiState.update { it.copy(debitLevel = value, error = null) }
    }

    fun onWaterTemperatureChanged(value: WaterTemperature) {
        _uiState.update { it.copy(waterTemperature = value, error = null) }
    }

    fun onAirTemperatureChanged(value: AirTemperature) {
        _uiState.update { it.copy(airTemperature = value, error = null) }
    }

    fun onCommentChanged(value: String) {
        _uiState.update { it.copy(comment = value, error = null) }
    }

    fun onPersonalCommentChanged(value: String) {
        _uiState.update { it.copy(personalComment = value, error = null) }
    }

    fun submit() {
        val state = _uiState.value
        val observerName = state.observerName.trim()
        val observerEmail = state.observerEmail.trim().takeIf { it.isNotBlank() }
        val observationType = state.observationType
        val debitLevel = state.debitLevel

        when {
            observerName.isBlank() -> {
                _uiState.update { it.copy(error = context.getString(R.string.debit_observer_name_required)) }
                return
            }
            !state.isConnected && observerEmail == null -> {
                _uiState.update { it.copy(error = context.getString(R.string.debit_observer_email_required_offline)) }
                return
            }
            observationType == null -> {
                _uiState.update { it.copy(error = context.getString(R.string.debit_observation_type_required)) }
                return
            }
            debitLevel == null -> {
                _uiState.update { it.copy(error = context.getString(R.string.debit_level_required)) }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, transientMessage = null) }
            submitDebitUseCase(
                DebitSubmission(
                    canyonId = canyonId,
                    observerName = observerName,
                    observerEmail = observerEmail,
                    observationDate = state.observationDate,
                    observationType = observationType,
                    debitLevel = debitLevel,
                    waterTemperature = state.waterTemperature ?: WaterTemperature.INCONNUE,
                    airTemperature = state.airTemperature ?: AirTemperature.INCONNUE,
                    comment = state.comment.trim(),
                    personalComment = state.personalComment.trim(),
                )
            ).fold(
                onSuccess = { status ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            transientMessage = when (status) {
                                DebitSubmissionStatus.SUBMITTED -> context.getString(R.string.debit_submission_sent)
                                DebitSubmissionStatus.QUEUED_OFFLINE -> context.getString(R.string.debit_submission_saved_offline)
                            },
                            lastSubmissionStatus = status,
                        )
                    }
                },
                onFailure = { throwable ->
                    val requiresLogin = throwable is DebitSubmissionSessionExpiredException
                    if (requiresLogin) {
                        authRepository.logout()
                    }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = if (requiresLogin) context.getString(R.string.debit_session_expired) else context.getString(R.string.debit_submission_failed),
                            loginRequiredMessage = if (requiresLogin) context.getString(R.string.debit_session_expired) else null,
                        )
                    }
                },
            )
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun clearLastSubmissionStatus() {
        _uiState.update { it.copy(lastSubmissionStatus = null) }
    }

    fun clearLoginRequiredMessage() {
        _uiState.update { it.copy(loginRequiredMessage = null) }
    }
}
