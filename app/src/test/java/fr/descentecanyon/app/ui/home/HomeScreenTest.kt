package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeScreenTest {

    @Test
    fun `latest debit item key stays unique when remote ids are default`() {
        val first = Debit(
            id = 0,
            canyonId = 220,
            canyonNom = "Ruzand",
            date = LocalDate.of(2026, 3, 17),
            niveau = NiveauDebit.CORRECT,
            auteur = "Alice",
        )
        val second = Debit(
            id = 0,
            canyonId = 2186,
            canyonNom = "Valouse",
            date = LocalDate.of(2026, 3, 22),
            niveau = NiveauDebit.FILET,
            auteur = "Bob",
        )

        assertNotEquals(latestDebitItemKey(first), latestDebitItemKey(second))
    }
}
