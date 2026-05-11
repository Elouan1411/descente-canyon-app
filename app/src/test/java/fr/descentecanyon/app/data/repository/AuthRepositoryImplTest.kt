package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.auth.CredentialStore
import fr.descentecanyon.app.data.remote.auth.LoginException
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.network.WebDocumentResponse
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
        sessionManager = SessionManager(object : DescenteCanyonWebClient() {
            override fun postDocument(
                url: String,
                data: Map<String, String>,
                cookies: Map<String, String>,
                referer: String?,
                origin: String?,
            ): WebDocumentResponse {
                throw LoginException("Login failed: invalid credentials")
            }
        })
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
        every { credentialStore.getUsername() } returns null
        every { credentialStore.getSessionCookies() } returns emptyMap()
        every { credentialStore.hasCredentials() } returns false

        val result = repository.tryRestoreSession()

        assertTrue(result.isFailure)
    }

    @Test
    fun `tryRestoreSession restores saved cookies without login`() = runTest {
        every { credentialStore.getUsername() } returns "antoine"
        every { credentialStore.getSessionCookies() } returns mapOf("sid" to "abc")

        val result = repository.tryRestoreSession()

        assertTrue(result.isSuccess)
        assertEquals(AuthState.Connected("antoine"), repository.authState.first())
        assertEquals(mapOf("sid" to "abc"), sessionManager.getCookies())
    }

    @Test
    fun `hasSavedCredentials delegates to store`() {
        every { credentialStore.hasSessionCookies() } returns false
        every { credentialStore.hasCredentials() } returns true
        assertTrue(repository.hasSavedCredentials())

        every { credentialStore.hasCredentials() } returns false
        assertFalse(repository.hasSavedCredentials())

        every { credentialStore.hasSessionCookies() } returns true
        assertTrue(repository.hasSavedCredentials())
    }
}
