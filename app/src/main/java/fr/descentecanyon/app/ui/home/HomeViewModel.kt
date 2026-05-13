package fr.descentecanyon.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.data.repository.HomeFeedSnapshotStore
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeFeedNotice {
    OFFLINE_BANNER,
    OFFLINE_EMPTY,
    STALE_BANNER,
    SERVICE_UNAVAILABLE,
}

data class HomeFeedSectionState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val notice: HomeFeedNotice? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

data class HomeUiState(
    val selectedFeed: HomeFeedType = HomeFeedType.DEBITS,
    val isOnline: Boolean = true,
    val debitFeed: HomeFeedSectionState<Debit> = HomeFeedSectionState(),
    val forumFeed: HomeFeedSectionState<ForumActiveTopic> = HomeFeedSectionState(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val debitRepository: DebitRepository,
    private val forumRepository: ForumRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val snapshotStore: HomeFeedSnapshotStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var latestDebitsJob: Job? = null
    private var activeTopicsJob: Job? = null
    private var previousOnline: Boolean? = null
    private var hasUserSelectedFeed = false

    init {
        observeConnectivity()
        restoreHomeState()
    }

    fun selectFeed(type: HomeFeedType) {
        hasUserSelectedFeed = true
        if (_uiState.value.selectedFeed == type) {
            if (uiState.value.isOnline) {
                refreshFeed(type)
            }
            return
        }

        _uiState.update { state -> state.copy(selectedFeed = type) }
        viewModelScope.launch {
            snapshotStore.writeSelectedFeedType(type)
        }

        if (uiState.value.isOnline) {
            refreshFeed(type)
        }
    }

    fun refreshSelectedFeed() {
        refreshFeed(uiState.value.selectedFeed)
    }

    private fun restoreHomeState() {
        viewModelScope.launch {
            val restoredSelectedFeed = snapshotStore.readSelectedFeedType() ?: HomeFeedType.DEBITS
            val cachedDebits = debitRepository.getCachedLatestDebits(LATEST_DEBITS_LIMIT)
            val cachedTopics = forumRepository.getCachedActiveTopics(ACTIVE_TOPICS_LIMIT)

            _uiState.update { state ->
                state.copy(
                    selectedFeed = if (hasUserSelectedFeed) state.selectedFeed else restoredSelectedFeed,
                    debitFeed = state.debitFeed.copy(
                        items = cachedDebits.items,
                        lastSyncedAtEpochMs = cachedDebits.syncedAtEpochMs,
                        notice = resolvePassiveNotice(
                            items = cachedDebits.items,
                            isOnline = state.isOnline,
                            currentNotice = null,
                        ),
                    ),
                    forumFeed = state.forumFeed.copy(
                        items = cachedTopics.items,
                        lastSyncedAtEpochMs = cachedTopics.syncedAtEpochMs,
                        notice = resolvePassiveNotice(
                            items = cachedTopics.items,
                            isOnline = state.isOnline,
                            currentNotice = null,
                        ),
                    ),
                )
            }

            if (connectivityObserver.isCurrentlyOnline()) {
                val selectedFeed = uiState.value.selectedFeed
                refreshFeed(selectedFeed)
                refreshFeed(otherFeedOf(selectedFeed), backgroundOnly = true)
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online ->
                val shouldRefresh = previousOnline == false && online
                previousOnline = online

                _uiState.update { state ->
                    state.copy(
                        isOnline = online,
                        debitFeed = state.debitFeed.copy(
                            notice = resolvePassiveNotice(
                                items = state.debitFeed.items,
                                isOnline = online,
                                currentNotice = state.debitFeed.notice,
                            ),
                        ),
                        forumFeed = state.forumFeed.copy(
                            notice = resolvePassiveNotice(
                                items = state.forumFeed.items,
                                isOnline = online,
                                currentNotice = state.forumFeed.notice,
                            ),
                        ),
                    )
                }

                if (shouldRefresh) {
                    val selectedFeed = uiState.value.selectedFeed
                    refreshFeed(selectedFeed)
                    refreshFeed(otherFeedOf(selectedFeed), backgroundOnly = true)
                }
            }
        }
    }

    private fun refreshFeed(
        feedType: HomeFeedType,
        backgroundOnly: Boolean = false,
    ) {
        if (!connectivityObserver.isCurrentlyOnline()) {
            _uiState.update { state ->
                when (feedType) {
                    HomeFeedType.DEBITS -> state.copy(
                        isOnline = false,
                        debitFeed = state.debitFeed.copy(
                            isLoading = false,
                            notice = resolveNotice(
                                items = state.debitFeed.items,
                                isOnline = false,
                                failureKind = HomeFeedFailureKind.NETWORK,
                            ),
                        ),
                    )

                    HomeFeedType.FORUM -> state.copy(
                        isOnline = false,
                        forumFeed = state.forumFeed.copy(
                            isLoading = false,
                            notice = resolveNotice(
                                items = state.forumFeed.items,
                                isOnline = false,
                                failureKind = HomeFeedFailureKind.NETWORK,
                            ),
                        ),
                    )
                }
            }
            return
        }

        when (feedType) {
            HomeFeedType.DEBITS -> {
                latestDebitsJob?.cancel()
                latestDebitsJob = viewModelScope.launch {
                    _uiState.update { state ->
                        state.copy(
                            debitFeed = state.debitFeed.copy(
                                isLoading = !backgroundOnly || state.selectedFeed == HomeFeedType.DEBITS,
                            ),
                        )
                    }

                    debitRepository.refreshLatestDebits(LATEST_DEBITS_LIMIT).fold(
                        onSuccess = { cached ->
                            _uiState.update { state ->
                                state.copy(
                                    debitFeed = state.debitFeed.copy(
                                        items = cached.items,
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = cached.items,
                                            isOnline = state.isOnline,
                                            failureKind = null,
                                        ),
                                        lastSyncedAtEpochMs = cached.syncedAtEpochMs,
                                    ),
                                )
                            }
                        },
                        onFailure = { throwable ->
                            _uiState.update { state ->
                                state.copy(
                                    debitFeed = state.debitFeed.copy(
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = state.debitFeed.items,
                                            isOnline = state.isOnline,
                                            failureKind = throwable.toFailureKind(state.isOnline),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            HomeFeedType.FORUM -> {
                activeTopicsJob?.cancel()
                activeTopicsJob = viewModelScope.launch {
                    _uiState.update { state ->
                        state.copy(
                            forumFeed = state.forumFeed.copy(
                                isLoading = !backgroundOnly || state.selectedFeed == HomeFeedType.FORUM,
                            ),
                        )
                    }

                    forumRepository.refreshActiveTopics(ACTIVE_TOPICS_LIMIT).fold(
                        onSuccess = { cached ->
                            _uiState.update { state ->
                                state.copy(
                                    forumFeed = state.forumFeed.copy(
                                        items = cached.items,
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = cached.items,
                                            isOnline = state.isOnline,
                                            failureKind = null,
                                        ),
                                        lastSyncedAtEpochMs = cached.syncedAtEpochMs,
                                    ),
                                )
                            }
                        },
                        onFailure = { throwable ->
                            _uiState.update { state ->
                                state.copy(
                                    forumFeed = state.forumFeed.copy(
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = state.forumFeed.items,
                                            isOnline = state.isOnline,
                                            failureKind = throwable.toFailureKind(state.isOnline),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun resolvePassiveNotice(
        items: List<*>,
        isOnline: Boolean,
        currentNotice: HomeFeedNotice?,
    ): HomeFeedNotice? {
        return when {
            !isOnline && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
            !isOnline && items.isEmpty() -> HomeFeedNotice.OFFLINE_EMPTY
            isOnline && (currentNotice == HomeFeedNotice.OFFLINE_BANNER || currentNotice == HomeFeedNotice.OFFLINE_EMPTY) -> null
            else -> currentNotice
        }
    }

    private fun resolveNotice(
        items: List<*>,
        isOnline: Boolean,
        failureKind: HomeFeedFailureKind?,
    ): HomeFeedNotice? {
        return when {
            !isOnline && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
            failureKind == HomeFeedFailureKind.NETWORK && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
            failureKind == HomeFeedFailureKind.NETWORK && items.isEmpty() -> HomeFeedNotice.OFFLINE_EMPTY
            failureKind == HomeFeedFailureKind.SERVICE && items.isNotEmpty() -> HomeFeedNotice.STALE_BANNER
            failureKind == HomeFeedFailureKind.SERVICE && items.isEmpty() -> HomeFeedNotice.SERVICE_UNAVAILABLE
            else -> null
        }
    }

    private fun Throwable.toFailureKind(isOnline: Boolean): HomeFeedFailureKind {
        return if (!isOnline || isLikelyNetworkIssue()) {
            HomeFeedFailureKind.NETWORK
        } else {
            HomeFeedFailureKind.SERVICE
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

    private fun otherFeedOf(type: HomeFeedType): HomeFeedType = when (type) {
        HomeFeedType.DEBITS -> HomeFeedType.FORUM
        HomeFeedType.FORUM -> HomeFeedType.DEBITS
    }

    private companion object {
        const val LATEST_DEBITS_LIMIT = 20
        const val ACTIVE_TOPICS_LIMIT = 12
    }
}

private enum class HomeFeedFailureKind {
    NETWORK,
    SERVICE,
}
