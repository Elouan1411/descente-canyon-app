package fr.descentecanyon.app.data.local.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.database.AppDatabase
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.room.withTransaction

@Singleton
class EmbeddedCanyonDataImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val canyonDao: CanyonDao,
    private val geoPointDao: GeoPointDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val appMetadataDao: AppMetadataDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureImported() {
        val manifest = readJsonAsset<RoomImportManifest>("manifest.json")
        val importedVersion = appMetadataDao.get(DATASET_VERSION_KEY)?.value
        if (importedVersion == manifest.generatedAt && canyonDao.count() > 0) {
            canyonDao.clearOfflineFlags()
            return
        }

        val favoriteIds = canyonDao.getFavoriteIds().toSet()

        val canyonRows = readJsonAsset<List<CanyonImportRow>>("canyons.json")
        val geoPointRows = readJsonAsset<List<GeoPointImportRow>>("geo_points.json")
        val bibliographyEntries = readJsonAsset<List<BibliographyEntryImportRow>>("bibliography_entries.json")
        val canyonBibliography = readJsonAsset<List<CanyonBibliographyImportRow>>("canyon_bibliography.json")
        val regulationTexts = readJsonAsset<List<RegulationImportRow>>("regulation_texts.json")
        val canyonRegulations = readJsonAsset<List<CanyonRegulationImportRow>>("canyon_regulations.json")

        database.withTransaction {
            bibliographyDao.clearLinks()
            regulationDao.clearLinks()
            bibliographyDao.clearEntries()
            regulationDao.clearTexts()
            canyonDao.clearAll()

            canyonRows.map { row -> row.toEntity(favoriteIds.contains(row.id), json) }
                .chunked(300)
                .forEach { chunk -> canyonDao.insertAll(chunk) }

            geoPointRows.map { row -> row.toEntity() }
                .chunked(500)
                .forEach { chunk -> geoPointDao.insertAll(chunk) }

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

    private inline fun <reified T> readJsonAsset(path: String): T {
        val payload = context.assets.open(path).bufferedReader().use { it.readText() }
        return json.decodeFromString(payload)
    }

    private fun CanyonImportRow.toEntity(isFavorite: Boolean, json: Json): CanyonEntity {
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
            isFavorite = isFavorite,
            lastUpdated = lastUpdated,
        )
    }

    private fun GeoPointImportRow.toEntity(): GeoPointEntity {
        return GeoPointEntity(
            canyonId = canyonId,
            type = type,
            latitude = latitude,
            longitude = longitude,
            label = label,
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

    companion object {
        private const val DATASET_VERSION_KEY = "embedded_dataset_version"
    }
}

@Serializable
private data class RoomImportManifest(
    val schemaVersion: Int,
    val generatedAt: String,
)

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
