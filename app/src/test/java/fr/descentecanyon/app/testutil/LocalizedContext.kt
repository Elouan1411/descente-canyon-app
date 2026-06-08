package fr.descentecanyon.app.testutil

import android.content.Context
import fr.descentecanyon.app.R
import io.mockk.every
import io.mockk.mockk

fun localizedContext(): Context {
    val context = mockk<Context>()
    val strings = mapOf(
        R.string.debit_observation_type_required to "Le type d'observation est obligatoire.",
        R.string.debit_level_required to "Le débit est obligatoire.",
        R.string.debit_submission_saved_offline to "Débit enregistré hors ligne",
        R.string.interest_rating_login_required to "Connecte-toi à ton compte Descente-Canyon avant de noter ce canyon.",
        R.string.interest_rating_saved to "Note enregistrée",
        R.string.photo_downloaded to "Photo téléchargée",
        R.string.weather_load_error_network to "Impossible de récupérer la météo pour le moment.",
        R.string.prediction_load_error_network to "Impossible de calculer l'estimation du débit pour le moment.",
        R.string.canyon_detail_load_error to "Impossible de charger cette fiche canyon pour le moment.",
    )
    every { context.getString(any()) } answers {
        strings[firstArg()] ?: "string-${firstArg<Int>()}"
    }
    return context
}
