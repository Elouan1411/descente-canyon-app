package fr.descentecanyon.app.ui.canyon

import androidx.compose.ui.graphics.Color
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.ui.theme.CanyonBlue
import fr.descentecanyon.app.ui.theme.CanyonBlueDark
import fr.descentecanyon.app.ui.theme.CotationDifficile
import fr.descentecanyon.app.ui.theme.CotationFacile
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.RockBrownLight
import java.util.Locale

fun GeoPoint.displayName(): String = when (type) {
    GeoPointType.PARKING_AMONT -> normalizedTitle() ?: "Parking Amont"
    GeoPointType.PARKING_AVAL -> normalizedTitle() ?: "Parking Aval"
    GeoPointType.ENTREE -> normalizedTitle() ?: "Départ du canyon"
    GeoPointType.SORTIE -> normalizedTitle() ?: "Sortie du canyon"
    GeoPointType.POINT_REMARQUABLE -> normalizedTitle() ?: "Point remarquable"
    GeoPointType.ECHAPPATOIRE -> normalizedTitle() ?: "Échappatoire"
    GeoPointType.UNKNOWN -> normalizedTitle() ?: "Point GPS"
}

fun GeoPoint.displaySubtitle(): String? {
    return remark?.trim()?.takeIf { it.isNotBlank() }
}

fun GeoPoint.navigationLabel(): String {
    return displayName()
}

private fun GeoPoint.normalizedTitle(): String? {
    val rawTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when (rawTitle.lowercase(Locale.FRENCH)) {
        "parking amont" -> "Parking Amont"
        "parking aval" -> "Parking Aval"
        "parking" -> "Parking"
        "départ du canyon", "depart du canyon" -> "Départ du canyon"
        "sortie du canyon" -> "Sortie du canyon"
        "point remarquable de l'approche ou du retour" -> "Point remarquable de l'approche ou du retour"
        "point remarquable à l'intérieur du canyon", "point remarquable a l'interieur du canyon" -> "Point remarquable à l'intérieur du canyon"
        else -> rawTitle.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.FRENCH) else char.toString() }
    }
}

fun GeoPointType.navigationPriority(): Int = when (this) {
    GeoPointType.PARKING_AMONT -> 0
    GeoPointType.PARKING_AVAL -> 1
    GeoPointType.ENTREE -> 2
    GeoPointType.SORTIE -> 3
    GeoPointType.POINT_REMARQUABLE -> 4
    GeoPointType.ECHAPPATOIRE -> 5
    GeoPointType.UNKNOWN -> 6
}

fun GeoPointType.mapColor(): Color = when (this) {
    GeoPointType.PARKING_AMONT -> CanyonBlue
    GeoPointType.PARKING_AVAL -> Color(0xFF7C3AED)
    GeoPointType.ENTREE -> CotationFacile
    GeoPointType.SORTIE -> CotationDifficile
    GeoPointType.POINT_REMARQUABLE -> RockBrownLight
    GeoPointType.ECHAPPATOIRE -> CanyonBlueDark
    GeoPointType.UNKNOWN -> DebitInconnu
}
