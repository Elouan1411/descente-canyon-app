package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.repository.EmbeddedDebitModelStore
import fr.descentecanyon.app.data.repository.EmbeddedDebitRuntimeLookupStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PredictionWarmupCoordinator @Inject constructor(
    private val modelStore: EmbeddedDebitModelStore,
    private val runtimeLookupStore: EmbeddedDebitRuntimeLookupStore,
) {

    private val warmupMutex = Mutex()

    @Volatile
    private var warmedUp = false

    suspend fun warmupIfNeeded() {
        if (warmedUp) return

        warmupMutex.withLock {
            if (warmedUp) return

            runCatching { runtimeLookupStore.getLookups() }
                .onFailure { throwable -> Log.w(TAG, "Unable to preload runtime lookups", throwable) }

            modelStore.getFeatureSpec()
            modelStore.getThresholds()
            modelStore.getStaticFeatures()
            modelStore.getSession()

            warmedUp = true
        }
    }

    private companion object {
        const val TAG = "PredictionWarmup"
    }
}
