package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.DebitSubmission
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
    val observationType: ObservationType = ObservationType.PARCOURU,
    val debitLevel: NiveauDebit = NiveauDebit.CORRECT,
    val waterTemperature: WaterTemperature = WaterTemperature.INCONNUE,
    val airTemperature: AirTemperature = AirTemperature.INCONNUE,
    val comment: String = "",
    val isConnected: Boolean = false,
    val pendingCount: Int = 0,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val transientMessage: String? = null,
    val lastSubmissionStatus: DebitSubmissionStatus? = null,
)

@HiltViewModel
class DebitFormViewModel @Inject constructor(
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
            _uiState.update { it.copy(observationDate = parsed, error = null) }
        }
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

    fun submit() {
        val state = _uiState.value
        val validationError = validate(state)
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, transientMessage = null) }
            submitDebitUseCase(
                DebitSubmission(
                    canyonId = canyonId,
                    observerName = state.observerName.trim(),
                    observerEmail = state.observerEmail.trim().takeIf { it.isNotBlank() },
                    observationDate = state.observationDate,
                    observationType = state.observationType,
                    debitLevel = state.debitLevel,
                    waterTemperature = state.waterTemperature,
                    airTemperature = state.airTemperature,
                    comment = state.comment.trim(),
                )
            ).fold(
                onSuccess = { status ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            transientMessage = when (status) {
                                DebitSubmissionStatus.SUBMITTED -> "Debit envoye"
                                DebitSubmissionStatus.QUEUED_OFFLINE -> "Debit enregistre hors-ligne"
                            },
                            lastSubmissionStatus = status,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = throwable.message ?: "Impossible d'envoyer le debit.",
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

    private fun validate(state: DebitFormUiState): String? {
        if (state.observerName.isBlank()) return "Le nom est obligatoire."
        if (!state.isConnected && state.observerEmail.isBlank()) return "L'email est obligatoire hors connexion."
        return null
    }
}
