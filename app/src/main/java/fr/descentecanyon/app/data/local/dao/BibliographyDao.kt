package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.entity.CanyonBibliographyEntity

@Dao
interface BibliographyDao {

    @Query(
        """
        SELECT be.*
        FROM bibliography_entries be
        INNER JOIN canyon_bibliography cb ON cb.bibliographyId = be.id
        WHERE cb.canyonId = :canyonId
        ORDER BY be.kind, be.title
        """
    )
    suspend fun getByCanyonId(canyonId: Int): List<BibliographyEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<BibliographyEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<CanyonBibliographyEntity>)

    @Query("DELETE FROM canyon_bibliography")
    suspend fun clearLinks()

    @Query("DELETE FROM bibliography_entries")
    suspend fun clearEntries()
}
