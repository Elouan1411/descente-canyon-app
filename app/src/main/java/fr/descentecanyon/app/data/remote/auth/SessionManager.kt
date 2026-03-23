package fr.descentecanyon.app.data.remote.auth

import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
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
class SessionManager @Inject constructor() {

    private val mutex = Mutex()
    @Volatile private var cookies: Map<String, String> = emptyMap()
    @Volatile private var _loggedInUsername: String? = null

    val isLoggedIn: Boolean get() = _loggedInUsername != null
    val loggedInUsername: String? get() = _loggedInUsername

    companion object {
        private const val LOGIN_URL = "${CanyonScraper.BASE_URL}/login"
        private const val USER_AGENT = "DescenteCanyonApp (Android)"
        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Perform login and store session cookies.
     * @return the logged-in username on success.
     * @throws LoginException on failure.
     */
    suspend fun login(username: String, password: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val response = Jsoup.connect(LOGIN_URL)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .data("username", username)
                .data("password", password)
                .method(Connection.Method.POST)
                .followRedirects(true)
                .execute()

            val responseCookies = response.cookies()
            val doc = response.parse()

            // Check if login succeeded: the navbar should show the username instead of "S'identifier"
            val navbarText = doc.select("ul.navbar-nav.navbar-right").text()
            val isSuccess = navbarText.contains(username, ignoreCase = true)
                || !navbarText.contains("identifier", ignoreCase = true)

            if (!isSuccess) {
                throw LoginException("Login failed: invalid credentials")
            }

            cookies = responseCookies
            _loggedInUsername = username
            username
        }
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

    /**
     * Apply session cookies to a JSoup connection.
     */
    fun applyTo(connection: Connection): Connection {
        return if (cookies.isNotEmpty()) {
            connection.cookies(cookies)
        } else {
            connection
        }
    }
}

class LoginException(message: String) : Exception(message)
