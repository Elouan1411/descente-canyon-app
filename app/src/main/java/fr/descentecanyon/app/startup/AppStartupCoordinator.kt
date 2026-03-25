package fr.descentecanyon.app.startup

import fr.descentecanyon.app.data.local.importer.EmbeddedCanyonDataImporter
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.usecase.SyncPendingDebitsUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val embeddedCanyonDataImporter: EmbeddedCanyonDataImporter,
    private val authRepository: AuthRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val syncPendingDebitsUseCase: SyncPendingDebitsUseCase,
) {

    private val initializeMutex = Mutex()
    @Volatile private var initialized = false

    suspend fun initialize() {
        if (initialized) return

        initializeMutex.withLock {
            if (initialized) return

            withContext(Dispatchers.IO) {
                embeddedCanyonDataImporter.ensureImported()
            }

            if (authRepository.hasSavedCredentials()) {
                authRepository.tryRestoreSession()
            }

            initialized = true
        }
    }

    fun observeConnectivity(): Flow<Boolean> = connectivityObserver.observe().distinctUntilChanged()

    suspend fun syncPendingDebitsIfOnline(isOnline: Boolean) {
        if (isOnline) {
            syncPendingDebitsUseCase()
        }
    }
}
