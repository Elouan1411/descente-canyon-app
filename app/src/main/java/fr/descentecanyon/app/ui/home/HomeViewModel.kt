package fr.descentecanyon.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.usecase.GetLatestDebitsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val latestDebits: List<Debit> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getLatestDebitsUseCase: GetLatestDebitsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadLatestDebits()
    }

    fun loadLatestDebits() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            getLatestDebitsUseCase(limit = 20).collect { result ->
                result.fold(
                    onSuccess = { debits ->
                        _uiState.update {
                            it.copy(
                                latestDebits = debits,
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
    }
}
