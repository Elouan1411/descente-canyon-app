package fr.descentecanyon.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.usecase.GetLatestDebitsUseCase
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomeLatestDebitsNotice {
    OFFLINE_BANNER,
    OFFLINE_EMPTY,
    SERVICE_UNAVAILABLE,
}

data class HomeUiState(
    val latestDebits: List<Debit> = emptyList(),
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val latestDebitsNotice: HomeLatestDebitsNotice? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getLatestDebitsUseCase: GetLatestDebitsUseCase,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var debitsJob: Job? = null
    private var previousOnline: Boolean? = null

    init {
        observeConnectivity()
        loadLatestDebits()
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online ->
                val shouldAutoRefresh = previousOnline == false && online
                previousOnline = online

                _uiState.update { state ->
                    state.copy(
                        isOnline = online,
                        latestDebitsNotice = when {
                            !online && state.latestDebits.isNotEmpty() -> HomeLatestDebitsNotice.OFFLINE_BANNER
                            !online && state.latestDebitsNotice == HomeLatestDebitsNotice.OFFLINE_EMPTY -> HomeLatestDebitsNotice.OFFLINE_EMPTY
                            online && (state.latestDebitsNotice == HomeLatestDebitsNotice.OFFLINE_BANNER ||
                                state.latestDebitsNotice == HomeLatestDebitsNotice.OFFLINE_EMPTY) -> null
                            else -> state.latestDebitsNotice
                        },
                    )
                }

                if (shouldAutoRefresh && !uiState.value.isLoading) {
                    loadLatestDebits()
                }
            }
        }
    }

    fun loadLatestDebits() {
        if (!connectivityObserver.isCurrentlyOnline()) {
            _uiState.update { state ->
                state.copy(
                    isOnline = false,
                    isLoading = false,
                    latestDebitsNotice = resolveNotice(
                        latestDebits = state.latestDebits,
                        isOnline = false,
                        failureKind = HomeLatestDebitsFailureKind.NETWORK,
                    ),
                )
            }
            return
        }

        debitsJob?.cancel()
        debitsJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoading = true,
                    latestDebitsNotice = if (state.latestDebits.isNotEmpty() && !state.isOnline) {
                        HomeLatestDebitsNotice.OFFLINE_BANNER
                    } else {
                        null
                    },
                )
            }
            getLatestDebitsUseCase(limit = 20).collect { result ->
                result.fold(
                    onSuccess = { debits ->
                        _uiState.update { state ->
                            state.copy(
                                latestDebits = debits,
                                isLoading = false,
                                latestDebitsNotice = resolveNotice(
                                    latestDebits = debits,
                                    isOnline = state.isOnline,
                                    failureKind = null,
                                ),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                latestDebitsNotice = resolveNotice(
                                    latestDebits = state.latestDebits,
                                    isOnline = state.isOnline,
                                    failureKind = throwable.toFailureKind(state.isOnline),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun resolveNotice(
        latestDebits: List<Debit>,
        isOnline: Boolean,
        failureKind: HomeLatestDebitsFailureKind?,
    ): HomeLatestDebitsNotice? {
        return when {
            !isOnline && latestDebits.isNotEmpty() -> HomeLatestDebitsNotice.OFFLINE_BANNER
            failureKind == HomeLatestDebitsFailureKind.NETWORK && latestDebits.isEmpty() -> HomeLatestDebitsNotice.OFFLINE_EMPTY
            failureKind == HomeLatestDebitsFailureKind.SERVICE && latestDebits.isEmpty() -> HomeLatestDebitsNotice.SERVICE_UNAVAILABLE
            else -> null
        }
    }

    private fun Throwable.toFailureKind(isOnline: Boolean): HomeLatestDebitsFailureKind {
        return if (!isOnline || isLikelyNetworkIssue()) {
            HomeLatestDebitsFailureKind.NETWORK
        } else {
            HomeLatestDebitsFailureKind.SERVICE
        }
    }

    private fun Throwable.isLikelyNetworkIssue(): Boolean {
        return generateSequence(this) { it.cause }.any { cause ->
            cause is UnknownHostException ||
                cause is UnresolvedAddressException ||
                cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                cause.message?.contains("No address associated with hostname", ignoreCase = true) == true
        }
    }
}

private enum class HomeLatestDebitsFailureKind {
    NETWORK,
    SERVICE,
}
