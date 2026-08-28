package fr.descentecanyon.app.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescenteCanyonWebClientTest {

    private val client = DescenteCanyonWebClient()

     @Test
     fun `trusted https host passes cookie guard`() {
        assertTrue(client.isCookieSafe("https://www.descente-canyon.com/canyoning"))
        assertTrue(client.isCookieSafe("https://descente-canyon.com/login"))
        assertTrue(client.isCookieSafe("https://app.descente-canyon.com/x"))
        assertTrue(client.isCookieSafe("https://WWW.DESCENTE-CANYON.COM/x"))
        }

     @Test
     fun `cleartext http host is rejected so cookies are not forwarded in plaintext`() {
        assertFalse(client.isCookieSafe("http://www.descente-canyon.com/canyoning"))
        assertFalse(client.isCookieSafe("http://127.0.0.1/login"))
        }

     @Test
     fun `off-domain host is rejected so session cookies cannot leak to a third party`() {
        assertFalse(client.isCookieSafe("https://evil.example.com/x"))
        assertFalse(client.isCookieSafe("https://descente-canyon.com.evil.example/x"))
        assertFalse(client.isCookieSafe("https://example.com/descente-canyon.com/x"))
        }

     @Test
     fun `malformed url is rejected`() {
        assertFalse(client.isCookieSafe("not a url"))
        assertFalse(client.isCookieSafe(""))
        }
    }
