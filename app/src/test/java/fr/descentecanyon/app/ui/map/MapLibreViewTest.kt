package fr.descentecanyon.app.ui.map

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
}
