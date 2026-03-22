package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.auth.CredentialStore
import fr.descentecanyon.app.data.remote.auth.LoginException
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.model.AuthState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthRepositoryImplTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var credentialStore: CredentialStore
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setup() {
        // Use a real SessionManager (it has no Android deps) instead of mocking
        // because MockK relaxed mocks don't handle Mutex/Lazy properly
        sessionManager = SessionManager()
        credentialStore = mockk(relaxed = true)
        repository = AuthRepositoryImpl(sessionManager, credentialStore)
    }

    @Test
    fun `initial state is disconnected`() = runTest {
        val state = repository.authState.first()
        assertEquals(AuthState.Disconnected, state)
    }

    @Test
    fun `login failure updates state to error`() = runTest {
        // Login will fail because we can't actually connect in unit tests
        val result = repository.login("user", "wrong")

        assertTrue(result.isFailure)
        val state = repository.authState.first()
        assertTrue("Expected Error state, got $state", state is AuthState.Error)
    }

    @Test
    fun `logout clears state`() = runTest {
        repository.logout()

        val state = repository.authState.first()
        assertEquals(AuthState.Disconnected, state)
        verify { credentialStore.clearCredentials() }
    }

    @Test
    fun `tryRestoreSession fails when no credentials`() = runTest {
        every { credentialStore.hasCredentials() } returns false

        val result = repository.tryRestoreSession()

        assertTrue(result.isFailure)
    }

    @Test
    fun `hasSavedCredentials delegates to store`() {
        every { credentialStore.hasCredentials() } returns true
        assertTrue(repository.hasSavedCredentials())

        every { credentialStore.hasCredentials() } returns false
        assertFalse(repository.hasSavedCredentials())
    }
}
