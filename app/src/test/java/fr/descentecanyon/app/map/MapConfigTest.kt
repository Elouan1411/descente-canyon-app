package fr.descentecanyon.app.map

import org.junit.Assert.assertTrue
import org.junit.Test

class MapConfigTest {

    @Test
    fun `offline bounds encloses requested center point`() {
        val bounds = createOfflineBounds(43.7, 6.9, radiusKm = 3.0)

        assertTrue(bounds.latitudeNorth > 43.7)
        assertTrue(bounds.latitudeSouth < 43.7)
        assertTrue(bounds.longitudeEast > 6.9)
        assertTrue(bounds.longitudeWest < 6.9)
    }
}
