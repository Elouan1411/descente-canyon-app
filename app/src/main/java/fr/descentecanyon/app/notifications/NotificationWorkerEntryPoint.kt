package fr.descentecanyon.app.notifications

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationWorkerEntryPoint {
    fun debitRepository(): DebitRepository
    fun forumRepository(): ForumRepository
    fun notificationCenterRepository(): NotificationCenterRepository
    fun appNotificationPublisher(): AppNotificationPublisher
}
