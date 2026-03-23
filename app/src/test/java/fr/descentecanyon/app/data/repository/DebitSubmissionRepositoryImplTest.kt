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
import kotlinx.coroutines.flow.Flow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DebitSubmissionRepositoryImplTest {

    private val pendingDao = mockk<PendingDebitSubmissionDao>(relaxed = true)
    private val remoteSource = mockk<DebitSubmissionRemoteSource>()

    @Test
    fun `offline submit queues observation`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = false
        }
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver)

        val result = repository.submit(sampleSubmission()).getOrThrow()

        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, result)
    }

    @Test
    fun `sync pending sends queued observations and clears them`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = true
        }
        coEvery { pendingDao.getAll() } returns listOf(
            PendingDebitSubmissionEntity(
                id = 9,
                canyonId = 2186,
                observerName = "Antoine",
                observerEmail = "antoine@example.com",
                observationDate = "2026-03-22",
                isDescended = true,
                debitLevel = NiveauDebit.CORRECT.name,
                waterTemperature = WaterTemperature.FROIDE.name,
                airTemperature = AirTemperature.BON.name,
                comment = "RAS",
            )
        )
        coEvery { remoteSource.submit(match { it.canyonId == 2186 }) } returns Result.success(Unit)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver)

        val synced = repository.syncPending().getOrThrow()

        assertEquals(1, synced)
        coVerify { remoteSource.submit(any()) }
        coVerify { pendingDao.deleteById(9) }
    }

    @Test
    fun `sync pending continues after individual failure`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = true
        }
        coEvery { pendingDao.getAll() } returns listOf(
            pendingEntity(id = 1, canyonId = 100),
            pendingEntity(id = 2, canyonId = 200),
            pendingEntity(id = 3, canyonId = 300),
        )
        // First succeeds, second fails, third succeeds
        coEvery { remoteSource.submit(match { it.canyonId == 100 }) } returns Result.success(Unit)
        coEvery { remoteSource.submit(match { it.canyonId == 200 }) } returns Result.failure(Exception("Server error"))
        coEvery { remoteSource.submit(match { it.canyonId == 300 }) } returns Result.success(Unit)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver)

        val synced = repository.syncPending().getOrThrow()

        assertEquals(2, synced)
        coVerify { pendingDao.deleteById(1) }
        coVerify(exactly = 0) { pendingDao.deleteById(2) }
        coVerify { pendingDao.deleteById(3) }
    }

    @Test
    fun `online submit falls back to queue on IOException`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = true
        }
        coEvery { remoteSource.submit(any()) } returns Result.failure(java.io.IOException("timeout"))
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver)

        val result = repository.submit(sampleSubmission()).getOrThrow()

        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, result)
    }

    private fun pendingEntity(id: Long, canyonId: Int) = PendingDebitSubmissionEntity(
        id = id,
        canyonId = canyonId,
        observerName = "Test",
        observerEmail = "test@example.com",
        observationDate = "2026-03-22",
        isDescended = true,
        debitLevel = NiveauDebit.CORRECT.name,
        waterTemperature = WaterTemperature.FROIDE.name,
        airTemperature = AirTemperature.BON.name,
        comment = "Test",
    )

    private fun sampleSubmission() = DebitSubmission(
        canyonId = 2186,
        observerName = "Antoine",
        observerEmail = "antoine@example.com",
        observationDate = LocalDate.of(2026, 3, 22),
        observationType = ObservationType.PARCOURU,
        debitLevel = NiveauDebit.CORRECT,
        waterTemperature = WaterTemperature.FROIDE,
        airTemperature = AirTemperature.BON,
        comment = "Super canyon",
    )
}
