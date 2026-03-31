package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * A water flow observation reported by a user.
 */
data class Debit(
    val id: Long = 0,
    val canyonId: Int,
    val canyonNom: String? = null,
    val date: LocalDate,
    val niveau: NiveauDebit,
    val auteur: String? = null,
    val isDescended: Boolean? = null,
    val waterTemperature: String? = null,
    val airTemperature: String? = null,
    val commentaire: String? = null,
)

/**
 * Water flow levels matching descente-canyon.com color codes.
 */
enum class NiveauDebit(val label: String, val colorHex: String) {
    SEC("Sec", "#999999"),
    FILET("Petit filet d'eau", "#0066FF"),
    CORRECT("Débit correct", "#00AA00"),
    GROS("Gros débit", "#FFAA00"),
    TRES_GROS("Très gros débit", "#FF6600"),
    CRUE("Trop d'eau", "#FF0000"),
    INCONNU("Inconnu", "#CCCCCC"),
}
