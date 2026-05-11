package fr.descentecanyon.app.data.remote.auth

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages HTTP session cookies for descente-canyon.com.
 *
 * The site uses a simple POST login with cookie-based sessions.
 * Login form: POST /login with fields "username" and "password".
 * On success, the server sets session cookies that must be sent with all subsequent requests.
 */
@Singleton
class SessionManager @Inject constructor(
    private val webClient: DescenteCanyonWebClient,
) {

    private val mutex = Mutex()
    @Volatile private var cookies: Map<String, String> = emptyMap()
    @Volatile private var _loggedInUsername: String? = null

    val isLoggedIn: Boolean get() = _loggedInUsername != null
    val loggedInUsername: String? get() = _loggedInUsername

    companion object {
        private const val LOGIN_URL = "${CanyonScraper.BASE_URL}/login"
    }

    /**
     * Perform login and store session cookies.
     * @return the logged-in username on success.
     * @throws LoginException on failure.
     */
    suspend fun login(username: String, password: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val response = webClient.postDocument(
                url = LOGIN_URL,
                data = mapOf(
                    "username" to username,
                    "password" to password,
                ),
            )
            val doc = response.document

            // Check if login succeeded: the navbar should show the username instead of "S'identifier"
            val navbarText = doc.select("ul.navbar-nav.navbar-right").text()
            val isSuccess = navbarText.contains(username, ignoreCase = true)
                || !navbarText.contains("identifier", ignoreCase = true)

            if (!isSuccess) {
                throw LoginException("Login failed: invalid credentials")
            }

            cookies = response.cookies
            _loggedInUsername = username
            username
        }
    }

    fun restoreSession(username: String, savedCookies: Map<String, String>) {
        cookies = savedCookies
        _loggedInUsername = username
    }

    /**
     * Clear the session.
     */
    fun logout() {
        cookies = emptyMap()
        _loggedInUsername = null
    }

    /**
     * Get current session cookies to attach to requests.
     */
    fun getCookies(): Map<String, String> = cookies

}

class LoginException(message: String) : Exception(message)
