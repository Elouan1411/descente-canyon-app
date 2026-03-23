package fr.descentecanyon.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import fr.descentecanyon.app.data.local.entity.DebitEntity
import fr.descentecanyon.app.data.local.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.local.entity.PhotoEntity

@Database(
    entities = [
        CanyonEntity::class,
        GeoPointEntity::class,
        DebitEntity::class,
        PhotoEntity::class,
        PendingDebitSubmissionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun canyonDao(): CanyonDao
    abstract fun geoPointDao(): GeoPointDao
    abstract fun debitDao(): DebitDao
    abstract fun photoDao(): PhotoDao
    abstract fun pendingDebitSubmissionDao(): PendingDebitSubmissionDao

    companion object {
        const val DATABASE_NAME = "descente_canyon_db"
    }
}
