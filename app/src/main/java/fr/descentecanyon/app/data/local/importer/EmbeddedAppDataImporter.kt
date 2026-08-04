package fr.descentecanyon.app.data.local.importer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.dao.ForumUserDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.dao.WatershedDao
import fr.descentecanyon.app.data.local.dao.getByIdsChunked
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.CanyonTrackEntity
import fr.descentecanyon.app.data.local.entity.ForumUserEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity
import fr.descentecanyon.app.data.local.entity.SearchIndexEntity
import fr.descentecanyon.app.data.local.entity.WatershedEntity
import fr.descentecanyon.app.data.mapper.toSearchIndexEntity
import fr.descentecanyon.app.data.mapper.toSearchItem
import fr.descentecanyon.app.data.mapper.withInferredSubdivisionsByCountry
import fr.descentecanyon.app.data.repository.RepresentativePointSelector
import fr.descentecanyon.app.perf.PerformanceTrace
import java.io.FileNotFoundException
import java.io.File
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
    private val canyonTrackDao: CanyonTrackDao,
    private val geoPointDao: GeoPointDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val watershedDao: WatershedDao,
    private val appMetadataDao: AppMetadataDao,
    private val searchIndexDao: SearchIndexDao,
    private val forumUserDao: ForumUserDao,
    private val representativePointSelector: RepresentativePointSelector,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureImported() {
        ensureCoreImported()
        ensureWatershedsImported()
    }

    suspend fun getCoreImportMode(): EmbeddedImportMode {
        return readCoreImportPlan().mode
    }

    suspend fun getWatershedsImportMode(): EmbeddedImportMode {
        val manifest = readManifest()
        val watershedsVersion = manifest.versions["watersheds"] ?: manifest.generatedAt
        val importedVersion = appMetadataDao.get(WATERSHEDS_VERSION_KEY)?.value
        val hasImportedWatersheds = watershedDao.count() > 0
        return if (shouldSkipWatershedsImport(importedVersion, watershedsVersion, hasImportedWatersheds)) {
            EmbeddedImportMode.SKIPPED
        } else if (hasImportedWatersheds) {
            EmbeddedImportMode.DATASET_UPDATE
        } else {
            EmbeddedImportMode.FIRST_IMPORT
        }
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
            val mappedRows = if (shouldImportFromPrepackagedDatabase("canyons.json")) {
                tracedImportPhase(
                    key = CORE_PREPACKAGED_DB_READ_TRACE_KEY,
                    event = "embedded_core_import_prepackaged_db_read",
                    "mode" to mode.logLabel,
                    "datasetVersion" to manifest.generatedAt,
                    onSuccess = { rows ->
                        arrayOf(
                            "importedRows" to rows.importedRowCount,
                            "canyonRows" to rows.canyonEntities.size,
                            "trackRows" to rows.canyonTrackEntities.size,
                            "forumUserRows" to rows.forumUserEntities.size,
                        )
                    },
                ) {
                    readCoreImportRowsFromPrepackagedDatabase()
                }
            } else {
                val payloads = tracedImportPhase(
                    key = CORE_JSON_READ_TRACE_KEY,
                    event = "embedded_core_import_json_read",
                    "mode" to mode.logLabel,
                    "datasetVersion" to manifest.generatedAt,
                    onSuccess = { phasePayloads ->
                        arrayOf(
                            "payloadBytes" to phasePayloads.totalPayloadBytes,
                            "tracksPayloadBytes" to phasePayloads.trackPayloadBytes,
                            "tracksAssetState" to phasePayloads.trackAssetState,
                        )
                    },
                ) {
                    readCoreAssetPayloads()
                }
                val decodedRows = tracedImportPhase(
                    key = CORE_JSON_DECODE_TRACE_KEY,
                    event = "embedded_core_import_json_decode",
                    "mode" to mode.logLabel,
                    "datasetVersion" to manifest.generatedAt,
                    onSuccess = { decoded ->
                        arrayOf(
                            "decodedRows" to decoded.importedRowCount,
                            "trackRows" to decoded.canyonTracks.size,
                        )
                    },
                ) {
                    decodeCoreImportPayloads(payloads)
                }
                tracedImportPhase(
                    key = CORE_MAPPING_TRACE_KEY,
                    event = "embedded_core_import_mapping",
                    "mode" to mode.logLabel,
                    "datasetVersion" to manifest.generatedAt,
                    onSuccess = { mapped ->
                        arrayOf(
                            "canyonEntities" to mapped.canyonEntities.size,
                            "geoPointEntities" to mapped.geoPointEntities.size,
                            "trackEntities" to mapped.canyonTrackEntities.size,
                        )
                    },
                ) {
                    mapCoreImportRows(decodedRows)
                }
            }
            val searchIndexRows = tracedImportPhase(
                key = CORE_SEARCH_INDEX_TRACE_KEY,
                event = "embedded_core_import_search_index_build",
                "mode" to mode.logLabel,
                "datasetVersion" to manifest.generatedAt,
                onSuccess = { rows -> arrayOf("rows" to rows.size) },
            ) {
                buildSearchIndexRows(mappedRows.canyonEntities, mappedRows.geoPointEntities)
            }

            tracedImportPhase(
                key = CORE_DB_WRITE_TRACE_KEY,
                event = "embedded_core_import_db_write",
                "mode" to mode.logLabel,
                "datasetVersion" to manifest.generatedAt,
                "rows" to mappedRows.importedRowCount,
                onSuccess = {
                    arrayOf(
                        "databaseBytes" to currentDatabaseBytes(),
                        "searchIndexRows" to searchIndexRows.size,
                    )
                },
            ) {
                writeCoreImportRows(mappedRows, searchIndexRows, manifest.generatedAt)
            }

            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.CORE,
                mode = mode,
                version = manifest.generatedAt,
                expectedRowCount = manifest.expectedCoreRowCount,
                importedRowCount = mappedRows.importedRowCount,
            ).also { outcome ->
                PerformanceTrace.end(
                    key = CORE_IMPORT_TRACE_KEY,
                    outcome = "ok",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "importedRows" to outcome.importedRowCount,
                    "databaseBytes" to currentDatabaseBytes(),
                    "heapKb" to currentUsedHeapKilobytes(),
                )
            }
        } catch (throwable: Throwable) {
            PerformanceTrace.end(
                key = CORE_IMPORT_TRACE_KEY,
                outcome = "failed",
                "mode" to mode.logLabel,
                "datasetVersion" to manifest.generatedAt,
                "error" to (throwable.message ?: throwable::class.simpleName),
                "heapKb" to currentUsedHeapKilobytes(),
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
        val hasSearchIndex = searchIndexDao.count() >= (manifest.counts["canyons"] ?: 0)
        val expectedTracks = manifest.counts["tracks"] ?: 0
        val hasExpectedTracks = hasExpectedTrackRows(
            importedTrackCount = canyonTrackDao.count(),
            expectedTrackCount = expectedTracks,
        )
        val mode = if (importedVersion == manifest.generatedAt && hasCanyons && hasBibliography && hasRegulations && hasExpectedTracks && hasSearchIndex) {
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
            val importStats = if (shouldImportFromPrepackagedDatabase("watersheds.json")) {
                tracedImportPhase(
                    key = WATERSHEDS_PREPACKAGED_DB_READ_TRACE_KEY,
                    event = "embedded_watersheds_import_prepackaged_db_read",
                    "mode" to mode.logLabel,
                    "datasetVersion" to watershedsVersion,
                    onSuccess = { stats ->
                        arrayOf(
                            "importedRows" to stats.importedRowCount,
                            "chunkInserts" to stats.insertedChunkCount,
                        )
                    },
                ) {
                    importWatershedsFromPrepackagedDatabase(
                        watershedsVersion = watershedsVersion,
                        expectedRowCount = manifest.counts["watersheds"],
                    )
                }
            } else {
                tracedImportPhase(
                    key = WATERSHEDS_DB_WRITE_TRACE_KEY,
                    event = "embedded_watersheds_import_db_write",
                    "mode" to mode.logLabel,
                    "datasetVersion" to watershedsVersion,
                    onSuccess = { stats ->
                        arrayOf(
                            "importedRows" to stats.importedRowCount,
                            "chunkInserts" to stats.insertedChunkCount,
                            "databaseBytes" to currentDatabaseBytes(),
                        )
                    },
                ) {
                    var stats = WatershedImportStats(importedRowCount = 0, insertedChunkCount = 0)
                    database.withTransaction {
                        watershedDao.clearAll()
                        stats = importWatershedsFromAsset()
                        appMetadataDao.insert(AppMetadataEntity(WATERSHEDS_VERSION_KEY, watershedsVersion))
                    }
                    stats
                }
            }

            return EmbeddedImportOutcome(
                dataset = EmbeddedImportDataset.WATERSHEDS,
                mode = mode,
                version = watershedsVersion,
                expectedRowCount = manifest.counts["watersheds"],
                importedRowCount = importStats.importedRowCount,
            ).also { outcome ->
                PerformanceTrace.end(
                    key = WATERSHEDS_IMPORT_TRACE_KEY,
                    outcome = "ok",
                    "mode" to outcome.mode.logLabel,
                    "datasetVersion" to outcome.version,
                    "importedRows" to outcome.importedRowCount,
                    "databaseBytes" to currentDatabaseBytes(),
                    "heapKb" to currentUsedHeapKilobytes(),
                )
            }
        } catch (throwable: Throwable) {
            PerformanceTrace.end(
                key = WATERSHEDS_IMPORT_TRACE_KEY,
                outcome = "failed",
                "mode" to mode.logLabel,
                "datasetVersion" to watershedsVersion,
                "error" to (throwable.message ?: throwable::class.simpleName),
                "heapKb" to currentUsedHeapKilobytes(),
            )
            throw throwable
        }
    }

    private fun readCoreAssetPayloads(): CoreAssetPayloads {
        return CoreAssetPayloads(
            canyonRows = readAssetText("canyons.json"),
            geoPointRows = readAssetText("geo_points.json"),
            bibliographyEntries = readAssetText("bibliography_entries.json"),
            canyonBibliography = readAssetText("canyon_bibliography.json"),
            regulationTexts = readAssetText("regulation_texts.json"),
                canyonRegulations = readAssetText("canyon_regulations.json"),
                canyonTracks = readOptionalAssetText("tracks.json"),
                forumUsers = readOptionalAssetText("forum_users.json"),
            )
    }

    private fun decodeCoreImportPayloads(payloads: CoreAssetPayloads): DecodedCoreImportRows {
        return DecodedCoreImportRows(
            canyonRows = json.decodeFromString(ListSerializer(CanyonImportRow.serializer()), payloads.canyonRows),
            geoPointRows = json.decodeFromString(ListSerializer(GeoPointImportRow.serializer()), payloads.geoPointRows),
            bibliographyEntries = json.decodeFromString(ListSerializer(BibliographyEntryImportRow.serializer()), payloads.bibliographyEntries),
            canyonBibliography = json.decodeFromString(ListSerializer(CanyonBibliographyImportRow.serializer()), payloads.canyonBibliography),
            regulationTexts = json.decodeFromString(ListSerializer(RegulationImportRow.serializer()), payloads.regulationTexts),
            canyonRegulations = json.decodeFromString(ListSerializer(CanyonRegulationImportRow.serializer()), payloads.canyonRegulations),
            canyonTracks = payloads.canyonTracks?.let {
                json.decodeFromString(ListSerializer(CanyonTrackImportRow.serializer()), it)
            }.orEmpty(),
            forumUsers = payloads.forumUsers?.let {
                json.decodeFromString(ListSerializer(ForumUserImportRow.serializer()), it)
            }.orEmpty(),
        )
    }

    private suspend fun mapCoreImportRows(decodedRows: DecodedCoreImportRows): MappedCoreImportRows {
        val existingCanyons = canyonDao.getByIdsChunked(decodedRows.canyonRows.map { it.id }).associateBy { it.id }
        val canyonEntities = decodedRows.canyonRows.map { row ->
            val existing = existingCanyons[row.id]
            preserveLocalCanyonState(row.toEntity(json), existing)
        }
        return MappedCoreImportRows(
            canyonEntities = canyonEntities,
            geoPointEntities = decodedRows.geoPointRows.map { it.toEntity() },
            bibliographyEntryEntities = decodedRows.bibliographyEntries.map { it.toEntity(json) },
            canyonBibliographyEntities = decodedRows.canyonBibliography.map { it.toEntity() },
            regulationTextEntities = decodedRows.regulationTexts.map { it.toEntity(json) },
            canyonRegulationEntities = decodedRows.canyonRegulations.map { it.toEntity() },
            canyonTrackEntities = decodedRows.canyonTracks.map { it.toEntity(json) },
            forumUserEntities = decodedRows.forumUsers.map { it.toEntity() },
        )
    }

    private suspend fun readCoreImportRowsFromPrepackagedDatabase(): MappedCoreImportRows {
        val rawRows = withPrepackagedDatabaseAssetFile { prepackagedDatabaseFile ->
            val assetDatabase = SQLiteDatabase.openDatabase(prepackagedDatabaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                MappedCoreImportRows(
                    canyonEntities = assetDatabase.queryList(
                    "SELECT id, nom, nomComplet, pays, region, departement, commune, communesJson, massif, bassin, coursEau, cotation, altitudeDepart, denivele, longueur, cascadeMax, cordeMin, tempsApproche, tempsDescente, tempsRetour, navette, interet, nbVotes, url, accesAval, accesAmont, approche, descente, retour, engagement, periode, geologie, historique, remarques, hasSpecificRegulation, isForbidden, isOffline, isFavorite, lastUpdated, sourceType, sourceKey FROM canyons"
                    ) { cursor ->
                    CanyonEntity(
                        id = cursor.getInt("id"),
                        nom = cursor.getString("nom"),
                        nomComplet = cursor.getString("nomComplet"),
                        pays = cursor.getString("pays"),
                        region = cursor.getStringOrNull("region"),
                        departement = cursor.getStringOrNull("departement"),
                        commune = cursor.getString("commune"),
                        communesJson = cursor.getStringOrNull("communesJson"),
                        massif = cursor.getStringOrNull("massif"),
                        bassin = cursor.getStringOrNull("bassin"),
                        coursEau = cursor.getStringOrNull("coursEau"),
                        cotation = cursor.getString("cotation"),
                        altitudeDepart = cursor.getIntOrNull("altitudeDepart"),
                        denivele = cursor.getIntOrNull("denivele"),
                        longueur = cursor.getIntOrNull("longueur"),
                        cascadeMax = cursor.getIntOrNull("cascadeMax"),
                        cordeMin = cursor.getIntOrNull("cordeMin"),
                        tempsApproche = cursor.getStringOrNull("tempsApproche"),
                        tempsDescente = cursor.getStringOrNull("tempsDescente"),
                        tempsRetour = cursor.getStringOrNull("tempsRetour"),
                        navette = cursor.getStringOrNull("navette"),
                        interet = cursor.getFloatOrNull("interet"),
                        nbVotes = cursor.getInt("nbVotes"),
                        url = cursor.getString("url"),
                        accesAval = cursor.getStringOrNull("accesAval"),
                        accesAmont = cursor.getStringOrNull("accesAmont"),
                        approche = cursor.getStringOrNull("approche"),
                        descente = cursor.getStringOrNull("descente"),
                        retour = cursor.getStringOrNull("retour"),
                        engagement = cursor.getStringOrNull("engagement"),
                        periode = cursor.getStringOrNull("periode"),
                        geologie = cursor.getStringOrNull("geologie"),
                        historique = cursor.getStringOrNull("historique"),
                        remarques = cursor.getStringOrNull("remarques"),
                        hasSpecificRegulation = cursor.getBoolean("hasSpecificRegulation"),
                        isForbidden = cursor.getBoolean("isForbidden"),
                        isOffline = cursor.getBoolean("isOffline"),
                        isFavorite = cursor.getBoolean("isFavorite"),
                        lastUpdated = cursor.getLong("lastUpdated"),
                        sourceType = cursor.getString("sourceType"),
                        sourceKey = cursor.getString("sourceKey"),
                    )
                    },
                    geoPointEntities = assetDatabase.queryList(
                    "SELECT canyonId, type, latitude, longitude, title, remark FROM geo_points"
                    ) { cursor ->
                    GeoPointEntity(
                        canyonId = cursor.getInt("canyonId"),
                        type = cursor.getString("type"),
                        latitude = cursor.getDouble("latitude"),
                        longitude = cursor.getDouble("longitude"),
                        title = cursor.getStringOrNull("title"),
                        remark = cursor.getStringOrNull("remark"),
                    )
                    },
                    bibliographyEntryEntities = assetDatabase.queryList(
                    "SELECT id, kind, resourceType, title, authorsJson, publicationYear, reference, editor, status, scale, detailUrl, url FROM bibliography_entries"
                    ) { cursor ->
                    BibliographyEntryEntity(
                        id = cursor.getString("id"),
                        kind = cursor.getString("kind"),
                        resourceType = cursor.getStringOrNull("resourceType"),
                        title = cursor.getString("title"),
                        authorsJson = cursor.getStringOrNull("authorsJson"),
                        publicationYear = cursor.getIntOrNull("publicationYear"),
                        reference = cursor.getStringOrNull("reference"),
                        editor = cursor.getStringOrNull("editor"),
                        status = cursor.getStringOrNull("status"),
                        scale = cursor.getStringOrNull("scale"),
                        detailUrl = cursor.getStringOrNull("detailUrl"),
                        url = cursor.getStringOrNull("url"),
                    )
                    },
                    canyonBibliographyEntities = assetDatabase.queryList(
                    "SELECT canyonId, bibliographyId FROM canyon_bibliography"
                    ) { cursor ->
                    CanyonBibliographyEntity(
                        canyonId = cursor.getInt("canyonId"),
                        bibliographyId = cursor.getString("bibliographyId"),
                    )
                    },
                    regulationTextEntities = assetDatabase.queryList(
                    "SELECT id, status, action, title, summary, remark, details, effectiveDate, textUrl, attachmentsJson FROM regulation_texts"
                    ) { cursor ->
                    RegulationTextEntity(
                        id = cursor.getInt("id"),
                        status = cursor.getStringOrNull("status"),
                        action = cursor.getStringOrNull("action"),
                        title = cursor.getString("title"),
                        summary = cursor.getStringOrNull("summary"),
                        remark = cursor.getStringOrNull("remark"),
                        details = cursor.getStringOrNull("details"),
                        effectiveDate = cursor.getStringOrNull("effectiveDate"),
                        textUrl = cursor.getString("textUrl"),
                        attachmentsJson = cursor.getStringOrNull("attachmentsJson"),
                    )
                    },
                    canyonRegulationEntities = assetDatabase.queryList(
                    "SELECT canyonId, regulationId FROM canyon_regulations"
                    ) { cursor ->
                    CanyonRegulationEntity(
                        canyonId = cursor.getInt("canyonId"),
                        regulationId = cursor.getInt("regulationId"),
                    )
                    },
                    canyonTrackEntities = assetDatabase.queryList(
                    "SELECT canyonId, trackId, name, role, isPrimary, sourceFile, pointCount, geometryJson, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude FROM canyon_tracks"
                    ) { cursor ->
                    CanyonTrackEntity(
                        canyonId = cursor.getInt("canyonId"),
                        trackId = cursor.getString("trackId"),
                        name = cursor.getString("name"),
                        role = cursor.getStringOrNull("role"),
                        isPrimary = cursor.getBoolean("isPrimary"),
                        sourceFile = cursor.getStringOrNull("sourceFile"),
                        pointCount = cursor.getIntOrNull("pointCount"),
                        geometryJson = cursor.getStringOrNull("geometryJson"),
                        bboxMinLongitude = cursor.getDoubleOrNull("bboxMinLongitude"),
                        bboxMinLatitude = cursor.getDoubleOrNull("bboxMinLatitude"),
                        bboxMaxLongitude = cursor.getDoubleOrNull("bboxMaxLongitude"),
                        bboxMaxLatitude = cursor.getDoubleOrNull("bboxMaxLatitude"),
                    )
                    },
                    forumUserEntities = assetDatabase.queryList(
                    "SELECT username, normalizedUsername, forumUserId, profileUrl, source, hasForumActivity, hasDebitActivity, forumPostCount, debitObservationCount, lastForumPostAt, lastForumPostUrl, lastDebitObservationAt, lastDebitObservationUrl, updatedAt FROM forum_users"
                    ) { cursor ->
                    ForumUserEntity(
                        username = cursor.getString("username"),
                        normalizedUsername = cursor.getString("normalizedUsername"),
                        forumUserId = cursor.getIntOrNull("forumUserId"),
                        profileUrl = cursor.getStringOrNull("profileUrl"),
                        source = cursor.getString("source"),
                        hasForumActivity = cursor.getBoolean("hasForumActivity"),
                        hasDebitActivity = cursor.getBoolean("hasDebitActivity"),
                        forumPostCount = cursor.getInt("forumPostCount"),
                        debitObservationCount = cursor.getInt("debitObservationCount"),
                        lastForumPostAt = cursor.getStringOrNull("lastForumPostAt"),
                        lastForumPostUrl = cursor.getStringOrNull("lastForumPostUrl"),
                        lastDebitObservationAt = cursor.getStringOrNull("lastDebitObservationAt"),
                        lastDebitObservationUrl = cursor.getStringOrNull("lastDebitObservationUrl"),
                        updatedAt = cursor.getString("updatedAt"),
                    )
                    },
                )
            } finally {
                assetDatabase.close()
            }
        }

        val existingCanyons = canyonDao.getByIdsChunked(rawRows.canyonEntities.map { it.id }).associateBy { it.id }
        return rawRows.copy(
            canyonEntities = rawRows.canyonEntities.map { canyon ->
                preserveLocalCanyonState(canyon, existingCanyons[canyon.id])
            },
        )
    }

    private suspend fun writeCoreImportRows(
        rows: MappedCoreImportRows,
        searchIndexRows: List<SearchIndexEntity>,
        datasetVersion: String,
    ) {
        database.withTransaction {
            bibliographyDao.clearLinks()
            regulationDao.clearLinks()
            bibliographyDao.clearEntries()
            regulationDao.clearTexts()

            rows.canyonEntities.forEach { canyon ->
                if (canyonDao.insertIgnore(canyon) == -1L) {
                    canyonDao.update(canyon)
                }
            }

            rows.geoPointEntities.groupBy { it.canyonId }
                .forEach { (canyonId, points) ->
                    geoPointDao.deleteByCanyonId(canyonId)
                    points.chunked(500).forEach { chunk -> geoPointDao.insertAll(chunk) }
                }

            searchIndexDao.clearAll()
            searchIndexRows.chunked(500).forEach { chunk -> searchIndexDao.insertAll(chunk) }

            rows.bibliographyEntryEntities
                .chunked(300)
                .forEach { chunk -> bibliographyDao.insertEntries(chunk) }

            rows.canyonBibliographyEntities
                .chunked(500)
                .forEach { chunk -> bibliographyDao.insertLinks(chunk) }

            rows.regulationTextEntities
                .chunked(300)
                .forEach { chunk -> regulationDao.insertTexts(chunk) }

            rows.canyonRegulationEntities
                .chunked(500)
                .forEach { chunk -> regulationDao.insertLinks(chunk) }

            canyonTrackDao.clearAll()
            rows.canyonTrackEntities
                .groupBy { it.canyonId }
                .forEach { (_, tracks) ->
                    tracks.chunked(300).forEach { chunk -> canyonTrackDao.insertAll(chunk) }
                }

            forumUserDao.clearAll()
            rows.forumUserEntities
                .chunked(500)
                .forEach { chunk -> forumUserDao.insertAll(chunk) }

            appMetadataDao.insert(AppMetadataEntity(DATASET_VERSION_KEY, datasetVersion))
        }
    }

    private fun readManifest(): RoomImportManifest {
        return readJsonAsset("manifest.json") { payload ->
            json.decodeFromString(RoomImportManifest.serializer(), payload)
        }
    }

    private suspend fun importWatershedsFromAsset(): WatershedImportStats {
        val input = try {
            context.assets.open("watersheds.json")
        } catch (_: FileNotFoundException) {
            return WatershedImportStats(importedRowCount = 0, insertedChunkCount = 0)
        }

        var importedCount = 0
        var insertedChunkCount = 0
        val chunk = mutableListOf<WatershedEntity>()
        input.bufferedReader().use { bufferedReader ->
            val reader = JsonReader(bufferedReader)
            reader.beginArray()
            while (reader.hasNext()) {
                readWatershedEntity(reader)?.let { entity ->
                    chunk += entity
                    importedCount += 1
                }
                if (chunk.size >= WATERSHED_IMPORT_CHUNK_SIZE) {
                    watershedDao.insertAll(chunk.toList())
                    insertedChunkCount += 1
                    chunk.clear()
                }
            }
            reader.endArray()
        }
        if (chunk.isNotEmpty()) {
            watershedDao.insertAll(chunk.toList())
            insertedChunkCount += 1
        }
        return WatershedImportStats(
            importedRowCount = importedCount,
            insertedChunkCount = insertedChunkCount,
        )
    }

    private fun readWatershedEntity(reader: JsonReader): WatershedEntity? {
        var canyonId: Int? = null
        var areaKm2: Double? = null
        var bbox: List<Double>? = null
        var geometryJson: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "canyonId" -> canyonId = readNullableInt(reader)
                "upstreamCatchmentAreaKm2" -> areaKm2 = readNullableDouble(reader)
                "bbox" -> bbox = readNullableDoubleList(reader)
                "geometry" -> geometryJson = readRawJsonValue(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val bboxValues = bbox?.takeIf { it.size == 4 }
        if (canyonId == null || geometryJson == null && areaKm2 == null && bboxValues == null) {
            return null
        }
        return WatershedEntity(
            canyonId = canyonId,
            areaKm2 = areaKm2,
            geometryJson = geometryJson,
            bboxMinLongitude = bboxValues?.get(0),
            bboxMinLatitude = bboxValues?.get(1),
            bboxMaxLongitude = bboxValues?.get(2),
            bboxMaxLatitude = bboxValues?.get(3),
        )
    }

    private fun readNullableInt(reader: JsonReader): Int? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextInt()
    }

    private fun readNullableDouble(reader: JsonReader): Double? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextDouble()
    }

    private fun readNullableDoubleList(reader: JsonReader): List<Double>? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }

        val values = mutableListOf<Double>()
        reader.beginArray()
        while (reader.hasNext()) {
            values += reader.nextDouble()
        }
        reader.endArray()
        return values
    }

    private fun readRawJsonValue(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }

        return buildString { appendRawJsonValue(reader, this) }
    }

    private fun appendRawJsonValue(reader: JsonReader, output: StringBuilder) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                output.append('[')
                var first = true
                while (reader.hasNext()) {
                    if (!first) output.append(',')
                    appendRawJsonValue(reader, output)
                    first = false
                }
                reader.endArray()
                output.append(']')
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                output.append('{')
                var first = true
                while (reader.hasNext()) {
                    if (!first) output.append(',')
                    appendJsonString(reader.nextName(), output)
                    output.append(':')
                    appendRawJsonValue(reader, output)
                    first = false
                }
                reader.endObject()
                output.append('}')
            }
            JsonToken.STRING -> appendJsonString(reader.nextString(), output)
            JsonToken.NUMBER -> output.append(reader.nextString())
            JsonToken.BOOLEAN -> output.append(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                output.append("null")
            }
            else -> reader.skipValue()
        }
    }

    private fun appendJsonString(value: String, output: StringBuilder) {
        output.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        output.append("\\u")
                        output.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        output.append(char)
                    }
                }
            }
        }
        output.append('"')
    }

    private fun <T> readJsonAsset(path: String, decode: (String) -> T): T {
        val payload = readAssetText(path)
        return decode(payload)
    }

    private fun readAssetText(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun hasAsset(path: String): Boolean {
        return runCatching {
            context.assets.open(path).use { }
            true
        }.getOrDefault(false)
    }

    private fun shouldImportFromPrepackagedDatabase(referenceJsonAsset: String): Boolean {
        return hasAsset(PREPACKAGED_DATABASE_ASSET_PATH) && !hasAsset(referenceJsonAsset)
    }

    private fun readOptionalAssetText(path: String): String? {
        return try {
            readAssetText(path)
        } catch (_: FileNotFoundException) {
            PerformanceTrace.logEvent(
                event = "embedded_import_optional_asset_missing",
                "path" to path,
            )
            null
        }
    }

    private suspend fun <T> tracedImportPhase(
        key: String,
        event: String,
        vararg startAttributes: Pair<String, Any?>,
        onSuccess: (T) -> Array<Pair<String, Any?>> = { emptyArray() },
        block: suspend () -> T,
    ): T {
        PerformanceTrace.start(key, event, *startAttributes)
        return try {
            val result = block()
            PerformanceTrace.end(
                key = key,
                outcome = "ok",
                *onSuccess(result),
                "heapKb" to currentUsedHeapKilobytes(),
            )
            result
        } catch (throwable: Throwable) {
            PerformanceTrace.end(
                key = key,
                outcome = "failed",
                "error" to (throwable.message ?: throwable::class.simpleName),
                "heapKb" to currentUsedHeapKilobytes(),
            )
            throw throwable
        }
    }

    private fun currentUsedHeapKilobytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024
    }

    private fun currentDatabaseBytes(): Long {
        val databaseFile = context.getDatabasePath(DescenteCanyonDatabase.DATABASE_NAME)
        val walFile = File("${databaseFile.path}-wal")
        val shmFile = File("${databaseFile.path}-shm")
        return listOf(databaseFile, walFile, shmFile)
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    private suspend fun <T> withPrepackagedDatabaseAssetFile(block: suspend (File) -> T): T {
        val tempDirectory = File(context.cacheDir, PREPACKAGED_DATABASE_CACHE_DIR_NAME).apply { mkdirs() }
        val tempFile = File(tempDirectory, PREPACKAGED_DATABASE_TEMP_FILE_NAME)
        context.assets.open(PREPACKAGED_DATABASE_ASSET_PATH).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }

        return try {
            block(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun <T> SQLiteDatabase.queryList(
        sql: String,
        mapper: (Cursor) -> T,
    ): List<T> {
        rawQuery(sql, emptyArray()).use { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.moveToNext()) {
                rows += mapper(cursor)
            }
            return rows
        }
    }

    private suspend fun importWatershedsFromPrepackagedDatabase(
        watershedsVersion: String,
        expectedRowCount: Int?,
    ): WatershedImportStats {
        return withPrepackagedDatabaseAssetFile { prepackagedDatabaseFile ->
            val watersheds = SQLiteDatabase.openDatabase(
                prepackagedDatabaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { assetDatabase ->
                assetDatabase.queryList(
                    "SELECT canyonId, areaKm2, geometryJson, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude FROM watersheds",
                ) { cursor ->
                    WatershedEntity(
                        canyonId = cursor.getInt("canyonId"),
                        areaKm2 = cursor.getDoubleOrNull("areaKm2"),
                        geometryJson = cursor.getStringOrNull("geometryJson"),
                        bboxMinLongitude = cursor.getDoubleOrNull("bboxMinLongitude"),
                        bboxMinLatitude = cursor.getDoubleOrNull("bboxMinLatitude"),
                        bboxMaxLongitude = cursor.getDoubleOrNull("bboxMaxLongitude"),
                        bboxMaxLatitude = cursor.getDoubleOrNull("bboxMaxLatitude"),
                    )
                }
            }

            require(hasExpectedWatershedRows(watersheds.size, expectedRowCount)) {
                "Prepackaged watershed count mismatch: expected $expectedRowCount, got ${watersheds.size}"
            }

            database.withTransaction {
                watershedDao.clearAll()
                watersheds.chunked(WATERSHED_IMPORT_CHUNK_SIZE).forEach { chunk ->
                    watershedDao.insertAll(chunk)
                }
                appMetadataDao.insert(AppMetadataEntity(WATERSHEDS_VERSION_KEY, watershedsVersion))
                WatershedImportStats(
                    importedRowCount = watersheds.size,
                    insertedChunkCount = watersheds.size.chunkCount(WATERSHED_IMPORT_CHUNK_SIZE),
                )
            }
        }
    }

    private fun Int.chunkCount(chunkSize: Int): Int {
        if (this <= 0) return 0
        return (this + chunkSize - 1) / chunkSize
    }

    private fun Cursor.getString(columnName: String): String = getString(getColumnIndexOrThrow(columnName))

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getInt(columnName: String): Int = getInt(getColumnIndexOrThrow(columnName))

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.getLong(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))

    private fun Cursor.getFloatOrNull(columnName: String): Float? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getFloat(index)
    }

    private fun Cursor.getDouble(columnName: String): Double = getDouble(getColumnIndexOrThrow(columnName))

    private fun Cursor.getDoubleOrNull(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.getBoolean(columnName: String): Boolean = getInt(columnName) != 0

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
            sourceType = sourceType,
            sourceKey = sourceKey.ifBlank { "dc:$id" },
        )
    }

    private fun buildSearchIndexRows(
        canyons: List<CanyonEntity>,
        geoPoints: List<GeoPointEntity>,
    ): List<SearchIndexEntity> {
        val representativePoints = geoPoints.groupBy { it.canyonId }
            .mapValues { (_, points) -> representativePointSelector.bestMarkerPointOrNull(points) }
        return canyons.map { canyon ->
            val point = representativePoints[canyon.id]
            canyon.toSearchItem(
                representativeLat = point?.latitude,
                representativeLng = point?.longitude,
            )
        }
            .withInferredSubdivisionsByCountry()
            .map { it.toSearchIndexEntity() }
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

    private fun CanyonTrackImportRow.toEntity(json: Json): CanyonTrackEntity {
        val geometryValue = geometry
            ?.takeUnless { it is JsonNull }
            ?.let { json.encodeToString(JsonElement.serializer(), it) }
        val bboxValues = bbox?.takeIf { it.size == 4 }
        return CanyonTrackEntity(
            canyonId = canyonId,
            trackId = trackId,
            name = name,
            role = role,
            isPrimary = isPrimary,
            sourceFile = sourceFile,
            pointCount = pointCount,
            geometryJson = geometryValue,
            bboxMinLongitude = bboxValues?.getOrNull(0),
            bboxMinLatitude = bboxValues?.getOrNull(1),
            bboxMaxLongitude = bboxValues?.getOrNull(2),
            bboxMaxLatitude = bboxValues?.getOrNull(3),
        )
    }

    private fun ForumUserImportRow.toEntity(): ForumUserEntity {
        return ForumUserEntity(
            username = username,
            normalizedUsername = normalizedUsername,
            forumUserId = forumUserId,
            profileUrl = profileUrl,
            source = source,
            hasForumActivity = hasForumActivity,
            hasDebitActivity = hasDebitActivity,
            forumPostCount = forumPostCount,
            debitObservationCount = debitObservationCount,
            lastForumPostAt = lastForumPostAt,
            lastForumPostUrl = lastForumPostUrl,
            lastDebitObservationAt = lastDebitObservationAt,
            lastDebitObservationUrl = lastDebitObservationUrl,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private const val DATASET_VERSION_KEY = "embedded_dataset_version"
        private const val WATERSHEDS_VERSION_KEY = "embedded_watersheds_version"
        private const val PREPACKAGED_DATABASE_ASSET_PATH = "databases/descente_canyon_prepackaged.db"
        private const val PREPACKAGED_DATABASE_CACHE_DIR_NAME = "prepackaged-db-import"
        private const val PREPACKAGED_DATABASE_TEMP_FILE_NAME = "descente_canyon_prepackaged-import.db"
        private const val CORE_IMPORT_TRACE_KEY = "embedded.import.core"
        private const val CORE_PREPACKAGED_DB_READ_TRACE_KEY = "embedded.import.core.prepackagedDbRead"
        private const val CORE_JSON_READ_TRACE_KEY = "embedded.import.core.jsonRead"
        private const val CORE_JSON_DECODE_TRACE_KEY = "embedded.import.core.jsonDecode"
        private const val CORE_MAPPING_TRACE_KEY = "embedded.import.core.mapping"
        private const val CORE_SEARCH_INDEX_TRACE_KEY = "embedded.import.core.searchIndex"
        private const val CORE_DB_WRITE_TRACE_KEY = "embedded.import.core.dbWrite"
        private const val WATERSHEDS_IMPORT_TRACE_KEY = "embedded.import.watersheds"
        private const val WATERSHEDS_PREPACKAGED_DB_READ_TRACE_KEY = "embedded.import.watersheds.prepackagedDbRead"
        private const val WATERSHEDS_DB_WRITE_TRACE_KEY = "embedded.import.watersheds.dbWrite"
        private const val WATERSHED_IMPORT_CHUNK_SIZE = 300
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

private data class CoreAssetPayloads(
    val canyonRows: String,
    val geoPointRows: String,
    val bibliographyEntries: String,
    val canyonBibliography: String,
    val regulationTexts: String,
    val canyonRegulations: String,
    val canyonTracks: String?,
    val forumUsers: String?,
) {
    val totalPayloadBytes: Int
        get() = listOfNotNull(
            canyonRows,
            geoPointRows,
            bibliographyEntries,
            canyonBibliography,
            regulationTexts,
            canyonRegulations,
            canyonTracks,
            forumUsers,
        ).sumOf { it.toByteArray().size }

    val trackPayloadBytes: Int
        get() = canyonTracks?.toByteArray()?.size ?: 0

    val trackAssetState: String
        get() = if (canyonTracks == null) "missing" else "present"
}

private data class DecodedCoreImportRows(
    val canyonRows: List<CanyonImportRow>,
    val geoPointRows: List<GeoPointImportRow>,
    val bibliographyEntries: List<BibliographyEntryImportRow>,
    val canyonBibliography: List<CanyonBibliographyImportRow>,
    val regulationTexts: List<RegulationImportRow>,
    val canyonRegulations: List<CanyonRegulationImportRow>,
    val canyonTracks: List<CanyonTrackImportRow>,
    val forumUsers: List<ForumUserImportRow>,
) {
    val importedRowCount: Int
        get() = canyonRows.size +
            geoPointRows.size +
            bibliographyEntries.size +
            canyonBibliography.size +
            regulationTexts.size +
            canyonRegulations.size +
            canyonTracks.size +
            forumUsers.size
}

private data class MappedCoreImportRows(
    val canyonEntities: List<CanyonEntity>,
    val geoPointEntities: List<GeoPointEntity>,
    val bibliographyEntryEntities: List<BibliographyEntryEntity>,
    val canyonBibliographyEntities: List<CanyonBibliographyEntity>,
    val regulationTextEntities: List<RegulationTextEntity>,
    val canyonRegulationEntities: List<CanyonRegulationEntity>,
    val canyonTrackEntities: List<CanyonTrackEntity>,
    val forumUserEntities: List<ForumUserEntity>,
) {
    val importedRowCount: Int
        get() = canyonEntities.size +
            geoPointEntities.size +
            bibliographyEntryEntities.size +
            canyonBibliographyEntities.size +
            regulationTextEntities.size +
            canyonRegulationEntities.size +
            canyonTrackEntities.size +
            forumUserEntities.size
}

private data class WatershedImportStats(
    val importedRowCount: Int,
    val insertedChunkCount: Int,
)

internal fun preserveLocalCanyonState(
    canyon: CanyonEntity,
    existing: CanyonEntity?,
): CanyonEntity {
    if (existing == null) return canyon
    return canyon.copy(
        communesJson = canyon.communesJson ?: existing.communesJson,
        bassin = canyon.bassin ?: existing.bassin,
        coursEau = canyon.coursEau ?: existing.coursEau,
        accesAval = canyon.accesAval ?: existing.accesAval,
        accesAmont = canyon.accesAmont ?: existing.accesAmont,
        approche = canyon.approche ?: existing.approche,
        descente = canyon.descente ?: existing.descente,
        retour = canyon.retour ?: existing.retour,
        engagement = canyon.engagement ?: existing.engagement,
        periode = canyon.periode ?: existing.periode,
        geologie = canyon.geologie ?: existing.geologie,
        historique = canyon.historique ?: existing.historique,
        remarques = canyon.remarques ?: existing.remarques,
        sourceType = canyon.sourceType.ifBlank { existing.sourceType },
        sourceKey = canyon.sourceKey.ifBlank { existing.sourceKey },
        hasSpecificRegulation = canyon.hasSpecificRegulation || existing.hasSpecificRegulation,
        isForbidden = canyon.isForbidden || existing.isForbidden,
        isOffline = existing.isOffline,
        isFavorite = existing.isFavorite,
        lastUpdated = maxOf(canyon.lastUpdated, existing.lastUpdated),
    )
}

internal fun shouldSkipWatershedsImport(
    importedVersion: String?,
    watershedsVersion: String,
    hasImportedWatersheds: Boolean,
): Boolean {
    return importedVersion == watershedsVersion && hasImportedWatersheds
}

internal fun hasExpectedTrackRows(importedTrackCount: Int, expectedTrackCount: Int): Boolean {
    return importedTrackCount >= expectedTrackCount
}

internal fun hasExpectedWatershedRows(importedRowCount: Int, expectedRowCount: Int?): Boolean {
    return expectedRowCount == null || importedRowCount == expectedRowCount
}

internal val RoomImportManifest.expectedCoreRowCount: Int
    get() = listOf(
        "canyons",
        "geo_points",
        "bibliography_entries",
        "canyon_bibliography",
        "regulation_texts",
        "canyon_regulations",
        "tracks",
        "forum_users",
    ).sumOf { counts[it] ?: 0 }
