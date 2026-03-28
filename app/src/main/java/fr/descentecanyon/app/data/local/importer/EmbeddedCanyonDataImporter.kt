package fr.descentecanyon.app.data.local.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.getByIdsChunked
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.database.AppDatabase
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.entity.WatershedEntity
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import androidx.room.withTransaction

@Singleton
class EmbeddedCanyonDataImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val watershedDao: WatershedDao,
    private val appMetadataDao: AppMetadataDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureImported() {
        ensureCoreImported()
        ensureWatershedsImported()
    }

    suspend fun ensureCoreImported() {
        val manifest = readJsonAsset<RoomImportManifest>("manifest.json")
        val importedVersion = appMetadataDao.get(DATASET_VERSION_KEY)?.value
        val hasCanyons = canyonDao.count() > 0
        val hasBibliography = bibliographyDao.countEntries() > 0
        val hasRegulations = regulationDao.countTexts() > 0
        if (importedVersion == manifest.generatedAt && hasCanyons && hasBibliography && hasRegulations) {
            return
        }

        val canyonRows = readJsonAsset<List<CanyonImportRow>>("canyons.json")
        val geoPointRows = readJsonAsset<List<GeoPointImportRow>>("geo_points.json")
        val bibliographyEntries = readJsonAsset<List<BibliographyEntryImportRow>>("bibliography_entries.json")
        val canyonBibliography = readJsonAsset<List<CanyonBibliographyImportRow>>("canyon_bibliography.json")
        val regulationTexts = readJsonAsset<List<RegulationImportRow>>("regulation_texts.json")
        val canyonRegulations = readJsonAsset<List<CanyonRegulationImportRow>>("canyon_regulations.json")
        val existingCanyons = canyonDao.getByIdsChunked(canyonRows.map { it.id }).associateBy { it.id }

        database.withTransaction {
            bibliographyDao.clearLinks()
            regulationDao.clearLinks()
            bibliographyDao.clearEntries()
            regulationDao.clearTexts()

            canyonRows.forEach { row ->
                val existing = existingCanyons[row.id]
                val merged = row.toEntity(json).preservingLocalState(existing)
                if (canyonDao.insertIgnore(merged) == -1L) {
                    canyonDao.update(merged)
                }
            }

            geoPointRows.map { row -> row.toEntity() }
                .groupBy { it.canyonId }
                .forEach { (canyonId, points) ->
                    geoPointDao.deleteByCanyonId(canyonId)
                    points.chunked(500).forEach { chunk -> geoPointDao.insertAll(chunk) }
                }

            bibliographyEntries.map { row -> row.toEntity(json) }
                .chunked(300)
                .forEach { chunk -> bibliographyDao.insertEntries(chunk) }

            canyonBibliography.map { row -> row.toEntity() }
                .chunked(500)
                .forEach { chunk -> bibliographyDao.insertLinks(chunk) }

            regulationTexts.map { row -> row.toEntity(json) }
                .chunked(300)
                .forEach { chunk -> regulationDao.insertTexts(chunk) }

            canyonRegulations.map { row -> row.toEntity() }
                .chunked(500)
                .forEach { chunk -> regulationDao.insertLinks(chunk) }

            appMetadataDao.insert(AppMetadataEntity(DATASET_VERSION_KEY, manifest.generatedAt))
        }
    }

    suspend fun ensureWatershedsImported() {
        val manifest = readJsonAsset<RoomImportManifest>("manifest.json")
        val watershedsVersion = manifest.watershedsVersion ?: manifest.generatedAt
        val importedVersion = appMetadataDao.get(WATERSHEDS_VERSION_KEY)?.value
        if (importedVersion == watershedsVersion) {
            return
        }

        val watersheds = readOptionalJsonAsset<List<WatershedImportRow>>("watersheds.json").orEmpty()
        database.withTransaction {
            watershedDao.clearAll()
            watersheds.mapNotNull { row -> row.toEntity(json) }
                .chunked(300)
                .forEach { chunk -> watershedDao.insertAll(chunk) }
            appMetadataDao.insert(AppMetadataEntity(WATERSHEDS_VERSION_KEY, watershedsVersion))
        }
    }

    private inline fun <reified T> readJsonAsset(path: String): T {
        val payload = context.assets.open(path).bufferedReader().use { it.readText() }
        return json.decodeFromString(payload)
    }

    private inline fun <reified T> readOptionalJsonAsset(path: String): T? {
        return try {
            readJsonAsset(path)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun CanyonImportRow.toEntity(json: Json): CanyonEntity {
        return CanyonEntity(
            id = id,
            nom = nom,
            nomComplet = nomComplet,
            pays = pays,
            region = region,
            departement = departement,
            commune = commune,
            communesJson = communes.takeIf { it.isNotEmpty() }?.let(json::encodeToString),
            massif = massif,
            bassin = bassin,
            coursEau = coursEau,
            cotation = cotation,
            altitudeDepart = altitudeDepart,
            denivele = denivele,
            longueur = longueur,
            cascadeMax = cascadeMax,
            cordeMin = cordeMin,
            tempsApproche = tempsApproche,
            tempsDescente = tempsDescente,
            tempsRetour = tempsRetour,
            navette = navette,
            interet = interet,
            nbVotes = nbVotes,
            url = url,
            accesAval = accesAval,
            accesAmont = accesAmont,
            approche = approche,
            descente = descente,
            retour = retour,
            engagement = engagement,
            periode = periode,
            geologie = geologie,
            historique = historique,
            remarques = remarques,
            hasSpecificRegulation = hasSpecificRegulation,
            isForbidden = isForbidden,
            isOffline = false,
            isFavorite = false,
            lastUpdated = lastUpdated,
        )
    }

    private fun CanyonEntity.preservingLocalState(existing: CanyonEntity?): CanyonEntity {
        if (existing == null) return this
        return copy(
            communesJson = communesJson ?: existing.communesJson,
            bassin = bassin ?: existing.bassin,
            coursEau = coursEau ?: existing.coursEau,
            accesAval = accesAval ?: existing.accesAval,
            accesAmont = accesAmont ?: existing.accesAmont,
            approche = approche ?: existing.approche,
            descente = descente ?: existing.descente,
            retour = retour ?: existing.retour,
            engagement = engagement ?: existing.engagement,
            periode = periode ?: existing.periode,
            geologie = geologie ?: existing.geologie,
            historique = historique ?: existing.historique,
            remarques = remarques ?: existing.remarques,
            hasSpecificRegulation = hasSpecificRegulation || existing.hasSpecificRegulation,
            isForbidden = isForbidden || existing.isForbidden,
            isOffline = existing.isOffline,
            isFavorite = existing.isFavorite,
            lastUpdated = maxOf(lastUpdated, existing.lastUpdated),
        )
    }

    private fun GeoPointImportRow.toEntity(): GeoPointEntity {
        return GeoPointEntity(
            canyonId = canyonId,
            type = type,
            latitude = latitude,
            longitude = longitude,
            title = label,
        )
    }

    private fun BibliographyEntryImportRow.toEntity(json: Json): BibliographyEntryEntity {
        return BibliographyEntryEntity(
            id = id,
            kind = kind,
            resourceType = resourceType,
            title = title,
            authorsJson = authors.takeIf { it.isNotEmpty() }?.let(json::encodeToString),
            publicationYear = publicationYear,
            reference = reference,
            editor = editor,
            status = status,
            scale = scale,
            detailUrl = detailUrl,
            url = url,
        )
    }

    private fun CanyonBibliographyImportRow.toEntity(): CanyonBibliographyEntity {
        return CanyonBibliographyEntity(
            canyonId = canyonId,
            bibliographyId = bibliographyId,
        )
    }

    private fun RegulationImportRow.toEntity(json: Json): RegulationTextEntity {
        return RegulationTextEntity(
            id = id,
            status = status,
            action = action,
            title = title,
            summary = summary,
            remark = remark,
            details = details,
            effectiveDate = effectiveDate,
            textUrl = textUrl,
            attachmentsJson = attachments.takeIf { it.isNotEmpty() }?.let(json::encodeToString),
        )
    }

    private fun CanyonRegulationImportRow.toEntity(): CanyonRegulationEntity {
        return CanyonRegulationEntity(
            canyonId = canyonId,
            regulationId = regulationId,
        )
    }

    private fun WatershedImportRow.toEntity(json: Json): WatershedEntity? {
        val geometryValue = geometry
            ?.takeUnless { it is JsonNull }
            ?.let { json.encodeToString(JsonElement.serializer(), it) }
        val bboxValues = bbox?.takeIf { it.size == 4 }
        if (geometryValue == null && upstreamCatchmentAreaKm2 == null && bboxValues == null) {
            return null
        }
        return WatershedEntity(
            canyonId = canyonId,
            areaKm2 = upstreamCatchmentAreaKm2,
            geometryJson = geometryValue,
            bboxMinLongitude = bboxValues?.get(0),
            bboxMinLatitude = bboxValues?.get(1),
            bboxMaxLongitude = bboxValues?.get(2),
            bboxMaxLatitude = bboxValues?.get(3),
        )
    }

    companion object {
        private const val DATASET_VERSION_KEY = "embedded_dataset_version"
        private const val WATERSHEDS_VERSION_KEY = "embedded_watersheds_version"
    }
}

@Serializable
private data class RoomImportManifest(
    val schemaVersion: Int,
    val generatedAt: String,
    val versions: Map<String, String> = emptyMap(),
)

private val RoomImportManifest.watershedsVersion: String?
    get() = versions["watersheds"]

@Serializable
private data class CanyonImportRow(
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
private data class GeoPointImportRow(
    val canyonId: Int,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)

@Serializable
private data class BibliographyEntryImportRow(
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
private data class CanyonBibliographyImportRow(
    val canyonId: Int,
    val bibliographyId: String,
)

@Serializable
private data class RegulationAttachmentImportRow(
    val label: String,
    val url: String,
)

@Serializable
private data class RegulationImportRow(
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
private data class CanyonRegulationImportRow(
    val canyonId: Int,
    val regulationId: Int,
)

@Serializable
private data class WatershedImportRow(
    val canyonId: Int,
    val upstreamCatchmentAreaKm2: Double? = null,
    val bbox: List<Double>? = null,
    val geometry: JsonElement? = null,
)
