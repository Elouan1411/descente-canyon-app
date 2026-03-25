package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.entity.RegulationTextEntity

@Dao
interface RegulationDao {

    @Query(
        """
        SELECT rt.*
        FROM regulation_texts rt
        INNER JOIN canyon_regulations cr ON cr.regulationId = rt.id
        WHERE cr.canyonId = :canyonId
        ORDER BY rt.title
        """
    )
    suspend fun getByCanyonId(canyonId: Int): List<RegulationTextEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTexts(texts: List<RegulationTextEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<CanyonRegulationEntity>)

    @Query("SELECT COUNT(*) FROM regulation_texts")
    suspend fun countTexts(): Int

    @Query("DELETE FROM canyon_regulations")
    suspend fun clearLinks()

    @Query("DELETE FROM regulation_texts")
    suspend fun clearTexts()
}
