package fr.descentecanyon.app.data.local.dao

import fr.descentecanyon.app.data.local.entity.CanyonEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonDaoExtensionsTest {

    @Test
    fun `get by ids chunked splits large collections into safe batches`() = runTest {
        val dao = mockk<CanyonDao>()
        val ids = (1..901).toList()

        coEvery { dao.getByIds((1..900).toList()) } returns listOf(canyonEntity(1), canyonEntity(900))
        coEvery { dao.getByIds(listOf(901)) } returns listOf(canyonEntity(901))

        val result = dao.getByIdsChunked(ids)

        assertEquals(listOf(1, 900, 901), result.map { it.id })
    }

    private fun canyonEntity(id: Int) = CanyonEntity(
        id = id,
        nom = "Canyon $id",
        nomComplet = "Canyon $id",
        pays = "France",
        commune = "Test",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
    )
}
