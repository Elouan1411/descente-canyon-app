package fr.descentecanyon.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreViewTest {

    @Test
    fun `detail points are shown when zoom reaches threshold`() {
        assertTrue(shouldShowDetailPoints(zoom = 8.4, visibleMarkerCount = 30))
    }

    @Test
    fun `detail points are shown when few canyons are visible`() {
        assertTrue(shouldShowDetailPoints(zoom = 7.2, visibleMarkerCount = 3))
    }

    @Test
    fun `clusters stay active when zoom is low and many canyons are visible`() {
        assertFalse(shouldShowDetailPoints(zoom = 7.2, visibleMarkerCount = 20))
    }

    @Test
    fun `interest marker colors follow descente canyon scale`() {
        assertEquals(0xFFFFFFFF.toInt(), interestMarkerColor(null))
        assertEquals(0xFFFFFFFF.toInt(), interestMarkerColor(0f))
        assertEquals(0xFFD6B27A.toInt(), interestMarkerColor(0.5f))
        assertEquals(0xFFFFD447.toInt(), interestMarkerColor(1.5f))
        assertEquals(0xFF33A852.toInt(), interestMarkerColor(2.5f))
        assertEquals(0xFF2F7DE1.toInt(), interestMarkerColor(3.5f))
        assertEquals(0xFF2F7DE1.toInt(), interestMarkerColor(4f))
    }
}
