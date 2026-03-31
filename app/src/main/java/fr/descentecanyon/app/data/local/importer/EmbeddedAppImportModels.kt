package fr.descentecanyon.app.data.local.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RoomImportManifest(
    val schemaVersion: Int,
    val generatedAt: String,
    val versions: Map<String, String> = emptyMap(),
)

@Serializable
data class CanyonImportRow(
    val id: Int,
    val nom: String,
    val nomComplet: String,
    val pays: String,
    val region: String? = null,
    val departement: String? = null,
    val commune: String,
    val communes: List<String> = emptyList(),
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val tempsApproche: String? = null,
    val tempsDescente: String? = null,
    val tempsRetour: String? = null,
    val navette: String? = null,
    val interet: Float? = null,
    val nbVotes: Int = 0,
    val url: String,
    val accesAval: String? = null,
    val accesAmont: String? = null,
    val approche: String? = null,
    val descente: String? = null,
    val retour: String? = null,
    val engagement: String? = null,
    val periode: String? = null,
    val geologie: String? = null,
    val historique: String? = null,
    val remarques: String? = null,
    val isOffline: Boolean = false,
    val isFavorite: Boolean = false,
    val lastUpdated: Long,
    val hasSpecificRegulation: Boolean = false,
    val isForbidden: Boolean = false,
)

@Serializable
data class GeoPointImportRow(
    val canyonId: Int,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)

@Serializable
data class BibliographyEntryImportRow(
    val id: String,
    val kind: String,
    val resourceType: String? = null,
    val title: String,
    val authors: List<String> = emptyList(),
    val publicationYear: Int? = null,
    val reference: String? = null,
    val editor: String? = null,
    val status: String? = null,
    val scale: String? = null,
    val detailUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class CanyonBibliographyImportRow(
    val canyonId: Int,
    val bibliographyId: String,
)

@Serializable
data class RegulationAttachmentImportRow(
    val label: String,
    val url: String,
)

@Serializable
data class RegulationImportRow(
    val id: Int,
    val status: String? = null,
    val action: String? = null,
    val title: String,
    val summary: String? = null,
    val remark: String? = null,
    val details: String? = null,
    val effectiveDate: String? = null,
    val textUrl: String,
    val attachments: List<RegulationAttachmentImportRow> = emptyList(),
)

@Serializable
data class CanyonRegulationImportRow(
    val canyonId: Int,
    val regulationId: Int,
)

@Serializable
data class WatershedImportRow(
    val canyonId: Int,
    val upstreamCatchmentAreaKm2: Double? = null,
    val bbox: List<Double>? = null,
    val geometry: JsonElement? = null,
)
