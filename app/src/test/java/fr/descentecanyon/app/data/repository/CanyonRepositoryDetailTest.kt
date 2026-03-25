package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CanyonRepositoryDetailTest {

    private val canyonDao = mockk<CanyonDao>()
    private val geoPointDao = mockk<GeoPointDao>()
    private val debitDao = mockk<DebitDao>()
    private val photoDao = mockk<PhotoDao>()
    private val bibliographyDao = mockk<BibliographyDao>()
    private val regulationDao = mockk<RegulationDao>()
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
            geoPointDao = geoPointDao,
            debitDao = debitDao,
            photoDao = photoDao,
            bibliographyDao = bibliographyDao,
            regulationDao = regulationDao,
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
