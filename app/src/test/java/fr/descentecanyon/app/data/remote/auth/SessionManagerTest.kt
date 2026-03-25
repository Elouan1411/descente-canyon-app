package fr.descentecanyon.app.data.remote.auth

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionManagerTest {

    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        sessionManager = SessionManager(DescenteCanyonWebClient())
    }

    @Test
    fun `initial state is not logged in`() {
        assertFalse(sessionManager.isLoggedIn)
        assertNull(sessionManager.loggedInUsername)
    }

    @Test
    fun `cookies are empty initially`() {
        assertTrue(sessionManager.getCookies().isEmpty())
    }

    @Test
    fun `logout clears state`() {
        // Even if never logged in, logout should be safe
        sessionManager.logout()
        assertFalse(sessionManager.isLoggedIn)
        assertNull(sessionManager.loggedInUsername)
        assertTrue(sessionManager.getCookies().isEmpty())
    }
}
