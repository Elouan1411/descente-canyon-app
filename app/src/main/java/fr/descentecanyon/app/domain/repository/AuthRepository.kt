package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for authentication with descente-canyon.com.
 */
interface AuthRepository {

    /** Observe the current auth state. */
    val authState: Flow<AuthState>

    /** Login with username and password. Returns true on success. */
    suspend fun login(username: String, password: String): Result<Unit>

    /** Logout and clear session. */
    suspend fun logout()

    /** Try to restore a previous session from saved credentials. */
    suspend fun tryRestoreSession(): Result<Unit>

    /** Check if credentials are saved (user chose "remember me"). */
    fun hasSavedCredentials(): Boolean
}
