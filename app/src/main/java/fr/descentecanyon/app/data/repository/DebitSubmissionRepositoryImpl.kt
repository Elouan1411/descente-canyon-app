package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebitSubmissionRepositoryImpl @Inject constructor(
    private val pendingDao: PendingDebitSubmissionDao,
    private val remoteSource: DebitSubmissionRemoteSource,
    private val connectivityObserver: ConnectivityObserver,
) : DebitSubmissionRepository {

    override suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            queueSubmission(submission)
            return Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        }

        return remoteSource.submit(submission)
            .map { DebitSubmissionStatus.SUBMITTED }
            .recoverCatching { throwable ->
                if (throwable.isRecoverableNetworkFailure()) {
                    queueSubmission(submission)
                    DebitSubmissionStatus.QUEUED_OFFLINE
                } else {
                    throw throwable
                }
            }
    }

    override suspend fun syncPending(): Result<Int> = runCatching {
        if (!connectivityObserver.isCurrentlyOnline()) return@runCatching 0

        var synced = 0
        pendingDao.getAll().forEach { pending ->
            runCatching {
                remoteSource.submit(pending.toDomain()).getOrThrow()
                pendingDao.deleteById(pending.id)
                synced += 1
            }
            // Individual failure is non-fatal; continue with next item
        }
        synced
    }

    override fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    private suspend fun queueSubmission(submission: DebitSubmission) {
        pendingDao.insert(submission.toEntity())
    }
}

private fun DebitSubmission.toEntity(): PendingDebitSubmissionEntity = PendingDebitSubmissionEntity(
    canyonId = canyonId,
    observerName = observerName,
    observerEmail = observerEmail,
    observationDate = observationDate.toString(),
    isDescended = observationType == ObservationType.PARCOURU,
    debitLevel = debitLevel.name,
    waterTemperature = waterTemperature.name,
    airTemperature = airTemperature.name,
    comment = comment,
)

private fun PendingDebitSubmissionEntity.toDomain(): DebitSubmission = DebitSubmission(
    canyonId = canyonId,
    observerName = observerName,
    observerEmail = observerEmail,
    observationDate = runCatching { LocalDate.parse(observationDate) }.getOrElse { LocalDate.now() },
    observationType = if (isDescended) ObservationType.PARCOURU else ObservationType.NON_PARCOURU,
    debitLevel = runCatching { NiveauDebit.valueOf(debitLevel) }.getOrDefault(NiveauDebit.INCONNU),
    waterTemperature = runCatching { WaterTemperature.valueOf(waterTemperature) }.getOrDefault(WaterTemperature.INCONNUE),
    airTemperature = runCatching { AirTemperature.valueOf(airTemperature) }.getOrDefault(AirTemperature.INCONNUE),
    comment = comment,
)

private fun Throwable.isRecoverableNetworkFailure(): Boolean {
    return this is IOException
}
