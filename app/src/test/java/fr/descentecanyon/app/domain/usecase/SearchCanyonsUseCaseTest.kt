package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.IntRangeFilter
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.repository.CanyonRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCanyonsUseCaseTest {

    private val useCase = SearchCanyonsUseCase(mockk<CanyonRepository>())

    @Test
    fun `difficulty sort compares strongest cotation component first`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, cotation = "V1A1V"),
                canyon(id = 2, cotation = "V4A4IV"),
                canyon(id = 3, cotation = "V4A1V"),
                canyon(id = 4, cotation = "V3A3V"),
            ),
            criteria = SearchCriteria(
                query = "canyon",
                sortField = SearchSortField.DIFFICULTY,
                sortDirection = SortDirection.DESC,
            ),
        )

        assertEquals(listOf(3, 4, 1, 2), result.results.map { it.id })
    }

    @Test
    fun `cotation min max filters apply independently`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, cotation = "V2A2II"),
                canyon(id = 2, cotation = "V4A4IV"),
                canyon(id = 3, cotation = "V5A2III"),
                canyon(id = 4, cotation = "??"),
            ),
            criteria = SearchCriteria(
                verticalRange = IntRangeFilter(min = 3, max = 5),
                aquaticRange = IntRangeFilter(max = 4),
                engagementRange = IntRangeFilter(min = 3),
            ),
        )

        assertEquals(setOf(2, 3), result.results.map { it.id }.toSet())
    }

    @Test
    fun `descending numeric sort keeps unknown values last`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1, longueur = 1000),
                canyon(id = 2, longueur = null),
                canyon(id = 3, longueur = 500),
            ),
            criteria = SearchCriteria(
                query = "canyon",
                sortField = SearchSortField.LENGTH,
                sortDirection = SortDirection.DESC,
            ),
        )

        assertEquals(listOf(1, 3, 2), result.results.map { it.id })
    }

    @Test
    fun `broad search is deferred until user types or filters`() {
        val result = useCase(
            catalog = listOf(
                canyon(id = 1),
                canyon(id = 2),
            ),
            criteria = SearchCriteria(),
        )

        assertTrue(result.isResultListDeferred)
        assertEquals(2, result.totalResultsCount)
        assertEquals(emptyList<Int>(), result.results.map { it.id })
    }

    @Test
    fun `country and subdivision filters match multi-valued entries`() {
        val result = useCase(
            catalog = listOf(
                canyon(
                    id = 1,
                    pays = "France, Espagne",
                    countryTokens = listOf("France", "Espagne"),
                    departement = "Pyrenees-Atlantiques, Huesca",
                    departmentTokens = listOf("Pyrenees-Atlantiques", "Huesca"),
                )
            ),
            criteria = SearchCriteria(
                selectedCountry = "Espagne",
                selectedDepartment = "Huesca",
            ),
        )

        assertEquals(listOf(1), result.results.map { it.id })
        assertEquals(listOf("Espagne", "France"), result.availableCountries)
        assertEquals(listOf("Huesca", "Pyrenees-Atlantiques"), result.availableDepartments)
    }

    private fun canyon(
        id: Int,
        cotation: String = "V3A3III",
        longueur: Int? = 1000,
        pays: String = "France",
        countryTokens: List<String> = listOf("France"),
        departement: String? = null,
        departmentTokens: List<String> = emptyList(),
    ) = CanyonSearchItem(
        id = id,
        nom = "Canyon $id",
        nomComplet = "Canyon $id",
        pays = pays,
        countryTokens = countryTokens,
        departement = departement,
        departmentTokens = departmentTokens,
        cotation = cotation,
        cotationRating = CotationRating.parse(cotation),
        longueur = longueur,
        url = "/canyoning/canyon/$id/test.html",
        searchableText = "canyon $id ${countryTokens.joinToString(" ")} ${departmentTokens.joinToString(" ")}".lowercase(),
    )
}
