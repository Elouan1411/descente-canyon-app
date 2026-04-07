package fr.descentecanyon.app.data.local.importer

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.dao.getByIdsChunked
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.entity.WatershedEntity
import fr.descentecanyon.app.perf.PerformanceTrace
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Singleton
class EmbeddedAppDataImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: DescenteCanyonDatabase,
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

    suspend fun getCoreImportMode(): EmbeddedImportMode {
        return readCoreImportPlan().mode
    }

    suspend fun ensureCoreImported(): EmbeddedImportOutcome {
        val plan = readCoreImportPlan()
        val manifest = plan.manifest
        if (plan.mode == EmbeddedImportMode.SKIPPED) {
            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.CORE,
                mode = EmbeddedImportMode.SKIPPED,
                version = manifest.generatedAt,
                expectedRowCount = manifest.expectedCoreRowCount,
                importedRowCount = 0,
            ).also { outcome ->
                PerformanceTrace.logEvent(
                    event = "embedded_core_import_skipped",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "expectedRows" to outcome.expectedRowCount,
                )
            }
        }

        val mode = plan.mode
        PerformanceTrace.start(
            key = CORE_IMPORT_TRACE_KEY,
            event = "embedded_core_import",
            "mode" to mode.logLabel,
            "datasetVersion" to manifest.generatedAt,
            "expectedRows" to manifest.expectedCoreRowCount,
        )

        try {
            val canyonRows = readCanyonRows()
            val geoPointRows = readGeoPointRows()
            val bibliographyEntries = readBibliographyEntries()
            val canyonBibliography = readCanyonBibliography()
            val regulationTexts = readRegulationTexts()
            val canyonRegulations = readCanyonRegulations()
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

                geoPointRows.map { it.toEntity() }
                    .groupBy { it.canyonId }
                    .forEach { (canyonId, points) ->
                        geoPointDao.deleteByCanyonId(canyonId)
                        points.chunked(500).forEach { chunk -> geoPointDao.insertAll(chunk) }
                    }

                bibliographyEntries.map { it.toEntity(json) }
                    .chunked(300)
                    .forEach { chunk -> bibliographyDao.insertEntries(chunk) }

                canyonBibliography.map { it.toEntity() }
                    .chunked(500)
                    .forEach { chunk -> bibliographyDao.insertLinks(chunk) }

                regulationTexts.map { it.toEntity(json) }
                    .chunked(300)
                    .forEach { chunk -> regulationDao.insertTexts(chunk) }

                canyonRegulations.map { it.toEntity() }
                    .chunked(500)
                    .forEach { chunk -> regulationDao.insertLinks(chunk) }

                appMetadataDao.insert(AppMetadataEntity(DATASET_VERSION_KEY, manifest.generatedAt))
            }

            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.CORE,
                mode = mode,
                version = manifest.generatedAt,
                expectedRowCount = manifest.expectedCoreRowCount,
                importedRowCount = canyonRows.size + geoPointRows.size + bibliographyEntries.size + canyonBibliography.size + regulationTexts.size + canyonRegulations.size,
            ).also { outcome ->
                PerformanceTrace.end(
                    key = CORE_IMPORT_TRACE_KEY,
                    outcome = "ok",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "importedRows" to outcome.importedRowCount,
                )
            }
        } catch (throwable: Throwable) {
            PerformanceTrace.end(
                key = CORE_IMPORT_TRACE_KEY,
                outcome = "failed",
                "mode" to mode.logLabel,
                "datasetVersion" to manifest.generatedAt,
                "error" to (throwable.message ?: throwable::class.simpleName),
            )
            throw throwable
        }
    }

    private suspend fun readCoreImportPlan(): CoreImportPlan {
        val manifest = readManifest()
        val importedVersion = appMetadataDao.get(DATASET_VERSION_KEY)?.value
        val hasCanyons = canyonDao.count() > 0
        val hasBibliography = bibliographyDao.countEntries() > 0
        val hasRegulations = regulationDao.countTexts() > 0
        val mode = if (importedVersion == manifest.generatedAt && hasCanyons && hasBibliography && hasRegulations) {
            EmbeddedImportMode.SKIPPED
        } else if (hasCanyons || hasBibliography || hasRegulations) {
            EmbeddedImportMode.DATASET_UPDATE
        } else {
            EmbeddedImportMode.FIRST_IMPORT
        }
        return CoreImportPlan(manifest = manifest, mode = mode)
    }

    suspend fun ensureWatershedsImported(): EmbeddedImportOutcome {
        val manifest = readManifest()
        val watershedsVersion = manifest.versions["watersheds"] ?: manifest.generatedAt
        val importedVersion = appMetadataDao.get(WATERSHEDS_VERSION_KEY)?.value
        val hasImportedWatersheds = watershedDao.count() > 0
        if (shouldSkipWatershedsImport(importedVersion, watershedsVersion, hasImportedWatersheds)) {
            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.WATERSHEDS,
                mode = EmbeddedImportMode.SKIPPED,
                version = watershedsVersion,
                expectedRowCount = manifest.counts["watersheds"],
                importedRowCount = 0,
            ).also { outcome ->
                PerformanceTrace.logEvent(
                    event = "embedded_watersheds_import_skipped",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "expectedRows" to outcome.expectedRowCount,
                )
            }
        }

        val mode = if (watershedDao.count() > 0) EmbeddedImportMode.DATASET_UPDATE else EmbeddedImportMode.FIRST_IMPORT
        PerformanceTrace.start(
            key = WATERSHEDS_IMPORT_TRACE_KEY,
            event = "embedded_watersheds_import",
            "mode" to mode.logLabel,
            "datasetVersion" to watershedsVersion,
            "expectedRows" to manifest.counts["watersheds"],
        )

        try {
            val watersheds = readWatershedRows().orEmpty()
            database.withTransaction {
                watershedDao.clearAll()
                watersheds.mapNotNull { it.toEntity(json) }
                    .chunked(300)
                    .forEach { chunk -> watershedDao.insertAll(chunk) }
                appMetadataDao.insert(AppMetadataEntity(WATERSHEDS_VERSION_KEY, watershedsVersion))
            }

            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.WATERSHEDS,
                mode = mode,
                version = watershedsVersion,
                expectedRowCount = manifest.counts["watersheds"],
                importedRowCount = watersheds.size,
            ).also { outcome ->
                PerformanceTrace.end(
                    key = WATERSHEDS_IMPORT_TRACE_KEY,
                    outcome = "ok",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "importedRows" to outcome.importedRowCount,
                )
            }
        } catch (throwable: Throwable) {
            PerformanceTrace.end(
                key = WATERSHEDS_IMPORT_TRACE_KEY,
                outcome = "failed",
                "mode" to mode.logLabel,
                "datasetVersion" to watershedsVersion,
                "error" to (throwable.message ?: throwable::class.simpleName),
            )
            throw throwable
        }
    }

    private fun readManifest(): RoomImportManifest {
        return readJsonAsset("manifest.json") { payload ->
            json.decodeFromString(RoomImportManifest.serializer(), payload)
        }
    }

    private fun readCanyonRows(): List<CanyonImportRow> {
        return readJsonAsset("canyons.json") { payload ->
            json.decodeFromString(ListSerializer(CanyonImportRow.serializer()), payload)
        }
    }

    private fun readGeoPointRows(): List<GeoPointImportRow> {
        return readJsonAsset("geo_points.json") { payload ->
            json.decodeFromString(ListSerializer(GeoPointImportRow.serializer()), payload)
        }
    }

    private fun readBibliographyEntries(): List<BibliographyEntryImportRow> {
        return readJsonAsset("bibliography_entries.json") { payload ->
            json.decodeFromString(ListSerializer(BibliographyEntryImportRow.serializer()), payload)
        }
    }

    private fun readCanyonBibliography(): List<CanyonBibliographyImportRow> {
        return readJsonAsset("canyon_bibliography.json") { payload ->
            json.decodeFromString(ListSerializer(CanyonBibliographyImportRow.serializer()), payload)
        }
    }

    private fun readRegulationTexts(): List<RegulationImportRow> {
        return readJsonAsset("regulation_texts.json") { payload ->
            json.decodeFromString(ListSerializer(RegulationImportRow.serializer()), payload)
        }
    }

    private fun readCanyonRegulations(): List<CanyonRegulationImportRow> {
        return readJsonAsset("canyon_regulations.json") { payload ->
            json.decodeFromString(ListSerializer(CanyonRegulationImportRow.serializer()), payload)
        }
    }

    private fun readWatershedRows(): List<WatershedImportRow>? {
        return readOptionalJsonAsset("watersheds.json") { payload ->
            json.decodeFromString(ListSerializer(WatershedImportRow.serializer()), payload)
        }
    }

    private fun <T> readJsonAsset(path: String, decode: (String) -> T): T {
        val payload = context.assets.open(path).bufferedReader().use { it.readText() }
        return decode(payload)
    }

    private fun <T> readOptionalJsonAsset(path: String, decode: (String) -> T): T? {
        return try {
            readJsonAsset(path, decode)
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
        return CanyonBibliographyEntity(canyonId = canyonId, bibliographyId = bibliographyId)
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
        return CanyonRegulationEntity(canyonId = canyonId, regulationId = regulationId)
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
        private const val CORE_IMPORT_TRACE_KEY = "embedded.import.core"
        private const val WATERSHEDS_IMPORT_TRACE_KEY = "embedded.import.watersheds"
    }
}

data class EmbeddedImportOutcome(
    val dataset: EmbeddedImportDataset,
    val mode: EmbeddedImportMode,
    val version: String,
    val expectedRowCount: Int?,
    val importedRowCount: Int,
)

enum class EmbeddedImportDataset {
    CORE,
    WATERSHEDS,
}

enum class EmbeddedImportMode(val logLabel: String) {
    SKIPPED("normal_launch"),
    FIRST_IMPORT("first_launch"),
    DATASET_UPDATE("dataset_update"),
}

private data class CoreImportPlan(
    val manifest: RoomImportManifest,
    val mode: EmbeddedImportMode,
)

internal fun shouldSkipWatershedsImport(
    importedVersion: String?,
    watershedsVersion: String,
    hasImportedWatersheds: Boolean,
): Boolean {
    return importedVersion == watershedsVersion && hasImportedWatersheds
}

private val RoomImportManifest.expectedCoreRowCount: Int
    get() = listOf(
        "canyons",
        "geo_points",
        "bibliography_entries",
        "canyon_bibliography",
        "regulation_texts",
        "canyon_regulations",
    ).sumOf { counts[it] ?: 0 }
