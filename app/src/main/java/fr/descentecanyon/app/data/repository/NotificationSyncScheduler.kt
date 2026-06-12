package fr.descentecanyon.app.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.notifications.NotificationSyncWorker
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class NotificationSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationCenterRepository: NotificationCenterRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            notificationCenterRepository.observeState()
                .map { it.hasTrackedTargets() }
                .distinctUntilChanged()
                .collect { hasTrackedTargets ->
                    if (hasTrackedTargets) {
                        schedulePeriodicSync()
                    } else {
                        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
                    }
                }
        }
    }

    private suspend fun schedulePeriodicSync() {
        val offsetMinutes = notificationCenterRepository.getOrCreateInstallOffsetMinutes()
        val initialDelayMinutes = minutesUntilNextSlot(offsetMinutes)
        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun minutesUntilNextSlot(offsetMinutes: Int): Long {
        val now = ZonedDateTime.now()
        val currentMinute = now.minute
        val minutes = listOf(offsetMinutes, (offsetMinutes + 30) % 60)
            .sorted()
            .firstOrNull { it > currentMinute }
            ?.let { nextMinute -> nextMinute - currentMinute }
            ?: (60 - currentMinute + offsetMinutes)
        val candidate = now.plusMinutes(minutes.toLong()).withSecond(0).withNano(0)
        return Duration.between(now, candidate).toMinutes().coerceAtLeast(0)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "notification-sync"
    }
}
