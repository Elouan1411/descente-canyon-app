package fr.descentecanyon.app.ui.offline

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.database.AppDatabase
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class OfflineManagerUiState(
    val offlineCanyons: List<CanyonSummary> = emptyList(),
    val isOnline: Boolean = true,
    val pendingDebitsCount: Int = 0,
    val storageSizeBytes: Long = 0L,
    val isLoading: Boolean = false,
    val transientMessage: String? = null,
)

@HiltViewModel
class OfflineManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val canyonRepository: CanyonRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val debitSubmissionRepository: DebitSubmissionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineManagerUiState())
    val uiState: StateFlow<OfflineManagerUiState> = _uiState.asStateFlow()

    init {
        observeData()
        computeStorageSize()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                canyonRepository.getOfflineCanyons(),
                connectivityObserver.observe(),
                debitSubmissionRepository.observePendingCount(),
            ) { canyons, online, pendingCount ->
                Triple(canyons, online, pendingCount)
            }.collect { (canyons, online, pendingCount) ->
                _uiState.update {
                    it.copy(
                        offlineCanyons = canyons,
                        isOnline = online,
                        pendingDebitsCount = pendingCount,
                    )
                }
            }
        }
    }

    fun removeOfflineCanyon(canyonId: Int) {
        viewModelScope.launch {
            canyonRepository.removeOfflineData(canyonId).fold(
                onSuccess = {
                    computeStorageSize()
                    _uiState.update {
                        it.copy(transientMessage = "Canyon supprime du mode hors-ligne")
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(transientMessage = throwable.message ?: "Erreur")
                    }
                },
            )
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun computeStorageSize() {
        viewModelScope.launch {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val dbSize = if (dbFile.exists()) dbFile.length() else 0L

            val mapLibreCacheDir = File(context.filesDir, "mbgl-offline.db")
            val mapSize = if (mapLibreCacheDir.exists()) mapLibreCacheDir.length() else 0L

            _uiState.update {
                it.copy(storageSizeBytes = dbSize + mapSize)
            }
        }
    }
}
