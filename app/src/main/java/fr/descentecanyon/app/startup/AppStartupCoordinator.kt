package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.local.importer.EmbeddedAppDataImporter
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.usecase.SyncPendingDebitsUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val embeddedCanyonDataImporter: EmbeddedAppDataImporter,
    private val authRepository: AuthRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val syncPendingDebitsUseCase: SyncPendingDebitsUseCase,
    private val predictionWarmupCoordinator: PredictionWarmupCoordinator,
) {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()
    @Volatile private var initialized = false

    suspend fun initialize() {
        if (initialized) return

        initializeMutex.withLock {
            if (initialized) return

            withContext(Dispatchers.IO) {
                embeddedCanyonDataImporter.ensureCoreImported()
            }

            if (authRepository.hasSavedCredentials()) {
                authRepository.tryRestoreSession()
            }

            initialized = true
            backgroundScope.launch {
                runCatching {
                    embeddedCanyonDataImporter.ensureWatershedsImported()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to import watersheds in background", throwable)
                }
            }
            backgroundScope.launch {
                delay(PREDICTION_WARMUP_DELAY_MS)
                runCatching {
                    predictionWarmupCoordinator.warmupIfNeeded()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to warm up prediction stack in background", throwable)
                }
            }
        }
    }

    fun observeConnectivity(): Flow<Boolean> = connectivityObserver.observe().distinctUntilChanged()

    suspend fun syncPendingDebitsIfOnline(isOnline: Boolean) {
        if (isOnline) {
            syncPendingDebitsUseCase()
        }
    }

    private companion object {
        const val TAG = "AppStartupCoordinator"
        const val PREDICTION_WARMUP_DELAY_MS = 4_000L
    }
}
