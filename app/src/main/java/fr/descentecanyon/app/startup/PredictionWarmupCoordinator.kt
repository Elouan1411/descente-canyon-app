package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.repository.EmbeddedDebitModelStore
import fr.descentecanyon.app.data.repository.EmbeddedDebitRuntimeLookupStore
import fr.descentecanyon.app.perf.PerformanceTrace
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
        if (warmedUp) {
            PerformanceTrace.logEvent("prediction_warmup_skipped", "reason" to "already_warmed_up")
            return
        }

        warmupMutex.withLock {
            if (warmedUp) {
                PerformanceTrace.logEvent("prediction_warmup_skipped", "reason" to "already_warmed_up")
                return
            }

            PerformanceTrace.start(WARMUP_TRACE_KEY, "prediction_warmup")
            try {
                runCatching { runtimeLookupStore.getLookups() }
                    .onFailure { throwable -> Log.w(TAG, "Unable to preload runtime lookups", throwable) }

                modelStore.getFeatureSpec()
                modelStore.getThresholds()
                modelStore.getStaticFeatures()
                modelStore.getSession()

                warmedUp = true
                PerformanceTrace.end(WARMUP_TRACE_KEY, outcome = "ok")
            } catch (throwable: Throwable) {
                PerformanceTrace.end(
                    key = WARMUP_TRACE_KEY,
                    outcome = "failed",
                    "error" to (throwable.message ?: throwable::class.simpleName),
                )
                throw throwable
            }
        }
    }

    private companion object {
        const val TAG = "PredictionWarmup"
        const val WARMUP_TRACE_KEY = "startup.prediction_warmup"
    }
}
