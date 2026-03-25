package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.CanyonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCanyonOfflineUseCaseTest {

    private val repository = mockk<CanyonRepository>()

    @Test
    fun `delegates canyon offline download to repository`() = runTest {
        coEvery { repository.downloadForOffline(2186) } returns Result.success(Unit)
        val useCase = DownloadCanyonOfflineUseCase(repository)

        val result = useCase(2186)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { repository.downloadForOffline(2186) }
    }
}
