package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.repository.EmbeddedDebitModelStore
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebitPredictionInfoUiState(
    val isLoading: Boolean = true,
    val summary: DebitPredictionInfoSummary? = null,
    val error: String? = null,
)

@HiltViewModel
class DebitPredictionInfoViewModel @Inject constructor(
    private val embeddedDebitModelStore: EmbeddedDebitModelStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebitPredictionInfoUiState())
    val uiState: StateFlow<DebitPredictionInfoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { embeddedDebitModelStore.getMetricsSummary() }
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            summary = summary,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            summary = null,
                            error = throwable.message ?: "Informations indisponibles",
                        )
                    }
                }
        }
    }
}
