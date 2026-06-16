package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.local.importer.EmbeddedAppDataImporter
import fr.descentecanyon.app.data.local.importer.EmbeddedImportMode
import fr.descentecanyon.app.data.repository.LegacyPhotoStorageMigrator
import fr.descentecanyon.app.data.repository.NotificationSyncScheduler
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val embeddedCanyonDataImporter: EmbeddedAppDataImporter,
    private val authRepository: AuthRepository,
    private val searchCatalogWarmupCoordinator: SearchCatalogWarmupCoordinator,
    private val predictionWarmupCoordinator: PredictionWarmupCoordinator,
    private val legacyPhotoStorageMigrator: LegacyPhotoStorageMigrator,
    private val notificationSyncScheduler: NotificationSyncScheduler,
) {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()
    @Volatile private var initialized = false

    suspend fun resolveLaunchMode(): StartupLaunchMode {
        val coreMode = withContext(Dispatchers.IO) { embeddedCanyonDataImporter.getCoreImportMode() }
        val watershedsMode = withContext(Dispatchers.IO) { embeddedCanyonDataImporter.getWatershedsImportMode() }
        val launchMode = if (coreMode == EmbeddedImportMode.SKIPPED && watershedsMode == EmbeddedImportMode.SKIPPED) {
            StartupLaunchMode.NON_BLOCKING
        } else {
            StartupLaunchMode.BLOCKING_IMPORT
        }
        PerformanceTrace.logEvent(
            event = "startup_launch_mode_resolved",
            "launchMode" to launchMode.logLabel,
            "coreImportMode" to coreMode.logLabel,
            "watershedsImportMode" to watershedsMode.logLabel,
        )
        return launchMode
    }

    suspend fun initialize() {
        if (initialized) return

        initializeMutex.withLock {
            if (initialized) return

            PerformanceTrace.logEvent("startup_initialize_enter")
            withContext(Dispatchers.IO) {
                val coreImportOutcome = embeddedCanyonDataImporter.ensureCoreImported()
                PerformanceTrace.logEvent(
                    event = "startup_core_ready",
                    "launchMode" to coreImportOutcome.mode.logLabel,
                    "datasetVersion" to coreImportOutcome.version,
                    "expectedRows" to coreImportOutcome.expectedRowCount,
                )
                val watershedsImportOutcome = embeddedCanyonDataImporter.ensureWatershedsImported()
                PerformanceTrace.logEvent(
                    event = "startup_watersheds_ready",
                    "launchMode" to watershedsImportOutcome.mode.logLabel,
                    "datasetVersion" to watershedsImportOutcome.version,
                    "expectedRows" to watershedsImportOutcome.expectedRowCount,
                    "importedRows" to watershedsImportOutcome.importedRowCount,
                )
            }

            if (authRepository.hasSavedCredentials()) {
                PerformanceTrace.start(AUTH_RESTORE_TRACE_KEY, "auth_restore_session")
                val restoreResult = authRepository.tryRestoreSession()
                PerformanceTrace.end(
                    key = AUTH_RESTORE_TRACE_KEY,
                    outcome = if (restoreResult.isSuccess) "restored" else "failed",
                )
            } else {
                PerformanceTrace.logEvent("auth_restore_session_skipped", "reason" to "no_saved_credentials")
            }

            notificationSyncScheduler.start()
            initialized = true
            PerformanceTrace.logEvent("startup_initialize_marked_ready")
            backgroundScope.launch {
                runCatching {
                    legacyPhotoStorageMigrator.migrateOrDeleteLegacyPhotos()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to migrate legacy photo storage", throwable)
                }

                delay(SEARCH_CATALOG_WARMUP_DELAY_MS)
                PerformanceTrace.logEvent("search_catalog_warmup_scheduled", "delayMs" to SEARCH_CATALOG_WARMUP_DELAY_MS)
                runCatching {
                    searchCatalogWarmupCoordinator.warmupIfNeeded()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to warm up search catalog in background", throwable)
                }

                delay(PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS)
                PerformanceTrace.logEvent("prediction_warmup_scheduled", "delayMs" to PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS)
                runCatching {
                    predictionWarmupCoordinator.warmupIfNeeded()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to warm up prediction stack in background", throwable)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppStartupCoordinator"
        const val SEARCH_CATALOG_WARMUP_DELAY_MS = 3_000L
        const val PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS = 2_500L
        const val AUTH_RESTORE_TRACE_KEY = "startup.auth_restore"
    }
}

enum class StartupLaunchMode(val logLabel: String) {
    NON_BLOCKING("normal_launch"),
    BLOCKING_IMPORT("blocking_import"),
}
