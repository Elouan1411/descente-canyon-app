package fr.descentecanyon.app.data.local.importer

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
}
