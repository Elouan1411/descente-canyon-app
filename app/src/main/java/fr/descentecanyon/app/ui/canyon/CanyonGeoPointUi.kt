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
    GeoPointType.PARKING_AMONT -> label ?: "Parking amont"
    GeoPointType.PARKING_AVAL -> label ?: "Parking aval"
    GeoPointType.ENTREE -> label ?: "Debut du canyon"
    GeoPointType.SORTIE -> label ?: "Sortie du canyon"
    GeoPointType.POINT_REMARQUABLE -> label ?: "Point remarquable"
    GeoPointType.ECHAPPATOIRE -> label ?: "Echappatoire"
    GeoPointType.UNKNOWN -> label ?: "Point GPS"
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
