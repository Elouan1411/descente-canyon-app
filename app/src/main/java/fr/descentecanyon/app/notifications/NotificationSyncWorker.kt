package fr.descentecanyon.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

class NotificationSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationWorkerEntryPoint::class.java,
        )
        val debitRepository = entryPoint.debitRepository()
        val forumRepository = entryPoint.forumRepository()
        val notificationCenterRepository = entryPoint.notificationCenterRepository()
        val appNotificationPublisher = entryPoint.appNotificationPublisher()
        val state = notificationCenterRepository.observeState().first()
        if (!state.hasTrackedTargets()) {
            return Result.success()
        }

        val debits = debitRepository.refreshLatestDebits(MAX_DEBITS_TO_SCAN)
            .getOrElse { return Result.retry() }
            .items
        val topics = forumRepository.refreshActiveTopics(MAX_TOPICS_TO_SCAN)
            .getOrElse { return Result.retry() }
            .items
        val summary = notificationCenterRepository.syncFetchedContent(
            latestDebits = debits,
            activeTopics = topics,
        )
        if (summary.newDebitEvents.isNotEmpty()) {
            appNotificationPublisher.publishDebitEvents(summary.newDebitEvents)
        }
        if (summary.newForumEvents.isNotEmpty()) {
            appNotificationPublisher.publishForumEvents(summary.newForumEvents)
        }
        return Result.success()
    }

    private companion object {
        const val MAX_DEBITS_TO_SCAN = 120
        const val MAX_TOPICS_TO_SCAN = 120
    }
}
