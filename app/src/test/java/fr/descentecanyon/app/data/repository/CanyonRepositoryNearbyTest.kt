package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.GeoPointType
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
    private val bibliographyDao = mockk<BibliographyDao>()
    private val regulationDao = mockk<RegulationDao>()
    private val scraper = mockk<CanyonScraper>(relaxed = true)

    @Test
    fun `get nearby canyons keeps closest markers within radius`() = runTest {
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
            bibliographyDao = bibliographyDao,
            regulationDao = regulationDao,
            scraper = scraper,
        )

        val result = repository.getCanyonsNearby(43.70, 6.90, radiusKm = 10.0).first().getOrThrow()

        assertEquals(listOf(1, 2), result.map { it.id })
        assertEquals(43.70, result.first().latitude)
        assertEquals(6.90, result.first().longitude)
        assertEquals(GeoPointType.PARKING_AMONT, result.first().markerType)
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
