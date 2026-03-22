package fr.descentecanyon.app.domain.model

/**
 * Represents the user's authentication state.
 */
sealed interface AuthState {
    data object Disconnected : AuthState
    data object Loading : AuthState
    data class Connected(val username: String) : AuthState
    data class Error(val message: String) : AuthState
}
