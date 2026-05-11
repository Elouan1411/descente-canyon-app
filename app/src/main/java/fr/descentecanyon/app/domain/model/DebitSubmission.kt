package fr.descentecanyon.app.domain.model

import java.time.LocalDate

data class DebitSubmission(
    val canyonId: Int,
    val observerName: String,
    val observerEmail: String? = null,
    val observationDate: LocalDate,
    val observationType: ObservationType,
    val debitLevel: NiveauDebit,
    val waterTemperature: WaterTemperature,
    val airTemperature: AirTemperature,
    val comment: String = "",
    val personalComment: String = "",
)

enum class ObservationType {
    NON_PARCOURU,
    PARCOURU,
}

enum class WaterTemperature {
    CHAUDE,
    DOUCE,
    FROIDE,
    TRES_FROIDE,
    GLACEE,
    INCONNUE,
}

enum class AirTemperature {
    SUPER_CHAUD,
    CHAUD,
    BON,
    FRISQUET,
    FROID,
    INCONNUE,
}

enum class DebitSubmissionStatus {
    SUBMITTED,
    QUEUED_OFFLINE,
}

class DebitSubmissionSessionExpiredException(
    message: String = "La session Descente-Canyon a expiré. Connecte-toi à nouveau avant d'envoyer le débit.",
) : Exception(message)
