package fr.descentecanyon.app.data.local.importer

import fr.descentecanyon.app.data.local.entity.CanyonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAppDataImporterTest {

    @Test
    fun `skip watershed import when version matches and rows already exist`() {
        assertTrue(
            shouldSkipWatershedsImport(
                importedVersion = "2026-03-30T14:09:06.540024+00:00",
                watershedsVersion = "2026-03-30T14:09:06.540024+00:00",
                hasImportedWatersheds = true,
            )
        )
    }

    @Test
    fun `do not skip watershed import when version matches but rows are missing`() {
        assertFalse(
            shouldSkipWatershedsImport(
                importedVersion = "2026-03-30T14:09:06.540024+00:00",
                watershedsVersion = "2026-03-30T14:09:06.540024+00:00",
                hasImportedWatersheds = false,
            )
        )
    }

    @Test
    fun `consider expected tracks satisfied when manifest expects none`() {
        assertTrue(
            hasExpectedTrackRows(
                importedTrackCount = 0,
                expectedTrackCount = 0,
            )
        )
    }

    @Test
    fun `do not consider expected tracks satisfied when manifest expects rows`() {
        assertFalse(
            hasExpectedTrackRows(
                importedTrackCount = 0,
                expectedTrackCount = 2,
            )
        )
    }

    @Test
    fun `require the expected watershed row count when manifest declares one`() {
        assertTrue(hasExpectedWatershedRows(importedRowCount = 3694, expectedRowCount = 3694))
        assertFalse(hasExpectedWatershedRows(importedRowCount = 3693, expectedRowCount = 3694))
    }

    @Test
    fun `allow watershed import when manifest has no count`() {
        assertTrue(hasExpectedWatershedRows(importedRowCount = 3694, expectedRowCount = null))
    }

    @Test
    fun `expected core row count includes tracks only when declared`() {
        val manifest = RoomImportManifest(
            schemaVersion = 1,
            generatedAt = "2026-06-16T00:00:00Z",
            counts = mapOf(
                "canyons" to 10,
                "geo_points" to 20,
                "bibliography_entries" to 3,
                "canyon_bibliography" to 4,
                "regulation_texts" to 5,
                "canyon_regulations" to 6,
                "tracks" to 0,
            ),
        )

        assertEquals(48, manifest.expectedCoreRowCount)
    }

    @Test
    fun `preserve local canyon state keeps favorite offline and notification sensitive local fields`() {
        val incoming = CanyonEntity(
            id = 1,
            nom = "Furon",
            nomComplet = "Furon aval",
            pays = "France",
            commune = "Sassenage",
            cotation = "v3 a3 III",
            url = "https://example.test/furon",
            isOffline = false,
            isFavorite = false,
            lastUpdated = 100L,
            sourceType = "DESCENTE_CANYON",
            sourceKey = "dc:1",
        )
        val existing = incoming.copy(
            bassin = "Bassin local",
            accesAval = "Acces local",
            hasSpecificRegulation = true,
            isForbidden = true,
            isOffline = true,
            isFavorite = true,
            lastUpdated = 200L,
            sourceKey = "local:1",
        )

        val preserved = preserveLocalCanyonState(incoming, existing)

        assertTrue(preserved.isFavorite)
        assertTrue(preserved.isOffline)
        assertTrue(preserved.hasSpecificRegulation)
        assertTrue(preserved.isForbidden)
        assertEquals("Bassin local", preserved.bassin)
        assertEquals("Acces local", preserved.accesAval)
        assertEquals("dc:1", preserved.sourceKey)
        assertEquals(200L, preserved.lastUpdated)
    }
}
