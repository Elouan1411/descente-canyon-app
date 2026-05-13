package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerifySequence
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonRepositoryDetailTest {

    private val canyonDao = mockk<CanyonDao>()
    private val canyonTrackDao = mockk<CanyonTrackDao>()
    private val geoPointDao = mockk<GeoPointDao>()
    private val debitDao = mockk<DebitDao>()
    private val photoDao = mockk<PhotoDao>()
    private val bibliographyDao = mockk<BibliographyDao>()
    private val regulationDao = mockk<RegulationDao>()
    private val watershedDao = mockk<WatershedDao>()
    private val searchIndexDao = mockk<SearchIndexDao>()
    private val database = mockk<DescenteCanyonDatabase>(relaxed = true)
    private val scraper = mockk<CanyonScraper>(relaxed = true)
    private val mapOfflineRepository = mockk<MapOfflineRepository>(relaxed = true)
    private val representativePointSelector = RepresentativePointSelector()

    @Test
    fun `insert preserving flags updates existing canyon without replace`() = runTest {
        val existing = canyonEntity(id = 42, nom = "Preview", isFavorite = true, isOffline = true)
        val refreshed = canyonEntity(id = 42, nom = "Riolan", isFavorite = false, isOffline = false)

        coEvery { canyonDao.getById(42) } returns existing
        coEvery { canyonDao.insertIgnore(any()) } returns -1L
        coJustRun { canyonDao.update(any()) }

        val localStore = CanyonLocalStore(
            canyonDao = canyonDao,
            canyonTrackDao = canyonTrackDao,
            geoPointDao = geoPointDao,
            debitDao = debitDao,
            photoDao = photoDao,
            bibliographyDao = bibliographyDao,
            regulationDao = regulationDao,
            watershedDao = watershedDao,
            representativePointSelector = representativePointSelector,
        )

        localStore.insertPreservingFlags(refreshed)

        coVerify(exactly = 1) { canyonDao.insertIgnore(any()) }
        coVerify(exactly = 1) {
            canyonDao.update(match {
                it.id == 42 &&
                    it.nom == "Riolan" &&
                    it.isFavorite &&
                    it.isOffline
            })
        }
    }

    @Test
    fun `get canyon detail loads local data without scraping`() = runTest {
        val canyon = canyonEntity(id = 42, nom = "Riolan", isFavorite = false, isOffline = true)
        val localStore = CanyonLocalStore(
            canyonDao = canyonDao,
            canyonTrackDao = canyonTrackDao,
            geoPointDao = geoPointDao,
            debitDao = debitDao,
            photoDao = photoDao,
            bibliographyDao = bibliographyDao,
            regulationDao = regulationDao,
            watershedDao = watershedDao,
            representativePointSelector = representativePointSelector,
        )

        coEvery { canyonDao.getById(42) } returns canyon
        coEvery { geoPointDao.getByCanyonId(42) } returns emptyList()
        coEvery { bibliographyDao.getByCanyonId(42) } returns emptyList()
        coEvery { regulationDao.getByCanyonId(42) } returns emptyList()
        coEvery { canyonTrackDao.getByCanyonId(42) } returns emptyList()
        coEvery { photoDao.getByCanyonId(42) } returns emptyList()
        coEvery { watershedDao.getByCanyonId(42) } returns null
        coEvery { debitDao.getByCanyonId(42) } returns flowOf(emptyList())

        val repository = CanyonRepositoryImpl(
            database = database,
            canyonDao = canyonDao,
            localStore = localStore,
            geoPointDao = geoPointDao,
            searchIndexDao = searchIndexDao,
            watershedDao = watershedDao,
            scraper = scraper,
            mapOfflineRepository = mapOfflineRepository,
        )

        val result = repository.getCanyonDetail(42).getOrThrow()

        assertEquals(canyon.id, result.canyon.id)
        assertEquals(canyon.nom, result.canyon.nom)
        coVerifySequence {
            canyonDao.getById(42)
            geoPointDao.getByCanyonId(42)
            bibliographyDao.getByCanyonId(42)
            regulationDao.getByCanyonId(42)
            canyonTrackDao.getByCanyonId(42)
            photoDao.getByCanyonId(42)
            debitDao.getByCanyonId(42)
            watershedDao.getByCanyonId(42)
        }
        confirmVerified(scraper)
    }

    private fun canyonEntity(
        id: Int,
        nom: String,
        isFavorite: Boolean,
        isOffline: Boolean,
    ) = CanyonEntity(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = "France",
        commune = "Test",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
        isFavorite = isFavorite,
        isOffline = isOffline,
    )
}
