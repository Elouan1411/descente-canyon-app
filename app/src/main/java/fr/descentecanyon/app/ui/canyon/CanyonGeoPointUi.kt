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

fun GeoPoint.displayName(): String = when (type) {
    GeoPointType.PARKING_AMONT -> label?.takeIf { it.isNotBlank() } ?: "Parking Amont"
    GeoPointType.PARKING_AVAL -> label?.takeIf { it.isNotBlank() } ?: "Parking Aval"
    GeoPointType.ENTREE -> label?.takeIf { it.isNotBlank() } ?: "Début du canyon"
    GeoPointType.SORTIE -> label?.takeIf { it.isNotBlank() } ?: "Sortie du canyon"
    GeoPointType.POINT_REMARQUABLE -> "Point remarquable"
    GeoPointType.ECHAPPATOIRE -> "Échappatoire"
    GeoPointType.UNKNOWN -> "Point GPS"
}

fun GeoPoint.displaySubtitle(): String? {
    val trimmedLabel = label?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val title = displayName()
    return trimmedLabel.takeUnless { it.equals(title, ignoreCase = true) }
}

fun GeoPoint.navigationLabel(): String {
    return displaySubtitle()?.let { subtitle -> "${displayName()} - $subtitle" } ?: displayName()
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
