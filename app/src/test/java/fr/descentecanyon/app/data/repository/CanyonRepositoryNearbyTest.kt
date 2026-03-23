package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.data.remote.scraper.MapIndexRemoteSource
import fr.descentecanyon.app.data.remote.scraper.NearbyCanyonRemoteSource
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonRepositoryNearbyTest {

    private val canyonDao = mockk<CanyonDao>()
    private val geoPointDao = mockk<GeoPointDao>()
    private val debitDao = mockk<DebitDao>()
    private val photoDao = mockk<PhotoDao>()
    private val scraper = mockk<CanyonScraper>(relaxed = true)
    private val nearbyCanyonRemoteSource = mockk<NearbyCanyonRemoteSource>()
    private val mapIndexRemoteSource = mockk<MapIndexRemoteSource>()
    private val mapOfflineRepository = mockk<MapOfflineRepository>()

    @Test
    fun `get nearby canyons keeps closest markers within radius`() = runTest {
        // Remote endpoint fails -> falls through to local DB
        coEvery { nearbyCanyonRemoteSource.getNearbyCanyons(any(), any()) } returns Result.failure(Exception("offline"))
        coEvery { mapIndexRemoteSource.getMapIndex() } returns Result.success(emptyList())
        coEvery { geoPointDao.getAll() } returns listOf(
            GeoPointEntity(canyonId = 1, type = "PARKING_AMONT", latitude = 43.70, longitude = 6.90),
            GeoPointEntity(canyonId = 2, type = "ENTREE", latitude = 43.72, longitude = 6.95),
            GeoPointEntity(canyonId = 3, type = "PARKING_AVAL", latitude = 45.00, longitude = 7.00),
        )
        coEvery { canyonDao.getByIds(listOf(1, 2, 3)) } returns listOf(
            canyonEntity(id = 2, nom = "Aiglun"),
            canyonEntity(id = 1, nom = "Riolan"),
            canyonEntity(id = 3, nom = "Lointain"),
        )
        val repository = CanyonRepositoryImpl(
            canyonDao = canyonDao,
            geoPointDao = geoPointDao,
            debitDao = debitDao,
            photoDao = photoDao,
            scraper = scraper,
            nearbyCanyonRemoteSource = nearbyCanyonRemoteSource,
            mapIndexRemoteSource = mapIndexRemoteSource,
            mapOfflineRepository = mapOfflineRepository,
        )

        val result = repository.getCanyonsNearby(43.70, 6.90, radiusKm = 10.0).first().getOrThrow()

        assertEquals(listOf(1, 2), result.map { it.id })
        assertEquals(43.70, result.first().latitude)
        assertEquals(6.90, result.first().longitude)
        assertEquals(GeoPointType.PARKING_AMONT, result.first().markerType)
    }

    @Test
    fun `get nearby canyons uses remote endpoint when available`() = runTest {
        coEvery { nearbyCanyonRemoteSource.getNearbyCanyons(43.70, 6.90) } returns Result.success(
            listOf(
                ScrapedCanyonSummary(id = 26, nom = "Furon (partie haute)", pays = "France", departement = "Isere", interet = 2.6f, url = "/canyoning/canyon/26/Furon.html", distanceKm = 0.4),
                ScrapedCanyonSummary(id = 27, nom = "Furon (partie basse)", pays = "France", departement = "Isere", interet = 2.4f, url = "/canyoning/canyon/27/Furon.html", distanceKm = 3.0),
            )
        )
        coEvery { mapIndexRemoteSource.getMapIndex() } returns Result.success(
            listOf(
                ScrapedCanyonSummary(id = 26, nom = "Furon (partie haute)", interet = 2.6f, latitude = 45.193, longitude = 5.628),
                ScrapedCanyonSummary(id = 27, nom = "Furon (partie basse)", interet = 2.4f, latitude = 45.207, longitude = 5.656),
            )
        )
        val repository = CanyonRepositoryImpl(
            canyonDao = canyonDao,
            geoPointDao = geoPointDao,
            debitDao = debitDao,
            photoDao = photoDao,
            scraper = scraper,
            nearbyCanyonRemoteSource = nearbyCanyonRemoteSource,
            mapIndexRemoteSource = mapIndexRemoteSource,
            mapOfflineRepository = mapOfflineRepository,
        )

        val result = repository.getCanyonsNearby(43.70, 6.90, radiusKm = 50.0).first().getOrThrow()

        assertEquals(listOf(26, 27), result.map { it.id })
        assertEquals("Furon (partie haute)", result.first().nom)
        assertEquals("France", result.first().pays)
        assertEquals(2.6f, result.first().interet)
        assertEquals(45.193, result.first().latitude)
    }

    private fun canyonEntity(
        id: Int,
        nom: String,
    ) = CanyonEntity(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = "France",
        commune = "Test",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
    )
}
