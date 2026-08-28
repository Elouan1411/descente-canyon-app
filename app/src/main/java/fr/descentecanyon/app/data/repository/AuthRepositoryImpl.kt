package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.auth.CredentialStore
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Disconnected)
    override val authState: Flow<AuthState> = _authState.asStateFlow()

    override suspend fun login(username: String, password: String): Result<Unit> {
        _authState.value = AuthState.Loading
        return runCatching {
            sessionManager.login(username, password)
            credentialStore.saveCredentials(username)
            credentialStore.saveSessionCookies(username, sessionManager.getCookies())
             _authState.value = AuthState.Connected(username)
         }.onFailure { e ->
             _authState.value = AuthState.Error(e.message ?: "Login failed")
         }
     }

     override suspend fun logout() {
        sessionManager.logout()
        credentialStore.clearCredentials()
         _authState.value = AuthState.Disconnected
      }

    override suspend fun tryRestoreSession(): Result<Unit> {
        val savedUsername = credentialStore.getUsername()
        val savedCookies = credentialStore.getSessionCookies()
        if (savedUsername != null && savedCookies.isNotEmpty()) {
            sessionManager.restoreSession(savedUsername, savedCookies)
             _authState.value = AuthState.Connected(savedUsername)
            return Result.success(Unit)
         }

        return Result.failure(IllegalStateException("No saved credentials"))
      }

    override fun hasSavedCredentials(): Boolean = credentialStore.hasSessionCookies() || credentialStore.hasCredentials()
}
