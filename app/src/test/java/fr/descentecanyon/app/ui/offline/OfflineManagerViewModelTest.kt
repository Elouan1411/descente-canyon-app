package fr.descentecanyon.app.ui.offline

import android.content.Context
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineManagerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = mockk<Context>(relaxed = true) {
        every { getDatabasePath(any()) } returns File("/tmp/fake_db")
        every { filesDir } returns File("/tmp/fake_files")
    }
    private val canyonRepository = mockk<CanyonRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository>()

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `exposes offline canyons and connectivity state`() = runTest {
        every { canyonRepository.getOfflineCanyons() } returns flowOf(
            listOf(
                CanyonSummary(
                    id = 42,
                    nom = "Riolan",
                    pays = "France",
                    cotation = "v4a4III",
                    url = "/canyoning/canyon/42/riolan.html",
                    isOffline = true,
                )
            )
        )
        every { connectivityObserver.observe() } returns MutableStateFlow(false)
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(2)

        val viewModel = OfflineManagerViewModel(
            context = context,
            canyonRepository = canyonRepository,
            connectivityObserver = connectivityObserver,
            debitSubmissionRepository = debitSubmissionRepository,
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.offlineCanyons.size)
        assertEquals(false, viewModel.uiState.value.isOnline)
        assertEquals(2, viewModel.uiState.value.pendingDebitsCount)
    }

}
