package fr.descentecanyon.app.map

import fr.descentecanyon.app.domain.model.CanyonSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapConfigTest {

    @Test
    fun `cluster engine groups nearby canyons under zoom threshold`() {
        val result = MapClusterEngine.cluster(
            canyons = listOf(
                canyon(1, 43.70, 6.90),
                canyon(2, 43.73, 6.94),
                canyon(3, 44.80, 7.90),
            ),
            zoom = 8.5,
        )

        val clusters = result.filterIsInstance<MapDisplayMarker.Cluster>()
        val singles = result.filterIsInstance<MapDisplayMarker.Canyon>()
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.first().count)
        assertEquals(listOf(3), singles.map { it.canyon.id })
    }

    @Test
    fun `cluster engine keeps individual markers above threshold and adds user position`() {
        val result = MapClusterEngine.cluster(
            canyons = listOf(canyon(1, 43.70, 6.90), canyon(2, 43.73, 6.94)),
            zoom = 10.2,
            userLatitude = 43.71,
            userLongitude = 6.91,
        )

        assertEquals(2, result.filterIsInstance<MapDisplayMarker.Canyon>().size)
        assertEquals(1, result.filterIsInstance<MapDisplayMarker.User>().size)
    }

    @Test
    fun `offline bounds encloses requested center point`() {
        val bounds = createOfflineBounds(43.7, 6.9, radiusKm = 3.0)

        assertTrue(bounds.latitudeNorth > 43.7)
        assertTrue(bounds.latitudeSouth < 43.7)
        assertTrue(bounds.longitudeEast > 6.9)
        assertTrue(bounds.longitudeWest < 6.9)
    }

    private fun canyon(id: Int, latitude: Double, longitude: Double) = CanyonSummary(
        id = id,
        nom = "Canyon $id",
        pays = "France",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
        latitude = latitude,
        longitude = longitude,
    )
}
