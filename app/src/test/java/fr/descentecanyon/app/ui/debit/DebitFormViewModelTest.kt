package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.usecase.SubmitDebitUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DebitFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Disconnected)
    private val authRepository = mockk<AuthRepository>()
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository>()
    private val submitDebitUseCase = SubmitDebitUseCase(debitSubmissionRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `connected auth pre-fills observer name`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        val viewModel = DebitFormViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 2186)),
            authRepository = authRepository,
            submitDebitUseCase = submitDebitUseCase,
            debitSubmissionRepository = debitSubmissionRepository,
        )

        authStateFlow.value = AuthState.Connected("antoine")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals("antoine", viewModel.uiState.value.observerName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `submit emits queued offline message`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(1)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        val viewModel = DebitFormViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 2186)),
            authRepository = authRepository,
            submitDebitUseCase = submitDebitUseCase,
            debitSubmissionRepository = debitSubmissionRepository,
        )

        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("Debit enregistre hors-ligne", viewModel.uiState.value.transientMessage)
        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, viewModel.uiState.value.lastSubmissionStatus)
    }
}
