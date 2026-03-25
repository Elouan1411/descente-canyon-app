package fr.descentecanyon.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.dao.RegulationDao
import fr.descentecanyon.app.data.local.database.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_debit_submissions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `canyonId` INTEGER NOT NULL,
                    `observerName` TEXT NOT NULL,
                    `observerEmail` TEXT,
                    `observationDate` TEXT NOT NULL,
                    `isDescended` INTEGER NOT NULL,
                    `debitLevel` TEXT NOT NULL,
                    `waterTemperature` TEXT NOT NULL,
                    `airTemperature` TEXT NOT NULL,
                    `comment` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "communesJson", "TEXT")
            ensureCanyonColumn(db, "bassin", "TEXT")
            ensureCanyonColumn(db, "coursEau", "TEXT")
            ensureCanyonColumn(db, "geologie", "TEXT")
            ensureCanyonColumn(db, "historique", "TEXT")
            ensureCanyonColumn(db, "remarques", "TEXT")
            ensureCanyonColumn(db, "hasSpecificRegulation", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "communesJson", "TEXT")
            ensureCanyonColumn(db, "bassin", "TEXT")
            ensureCanyonColumn(db, "coursEau", "TEXT")
            ensureCanyonColumn(db, "geologie", "TEXT")
            ensureCanyonColumn(db, "historique", "TEXT")
            ensureCanyonColumn(db, "remarques", "TEXT")
            ensureCanyonColumn(db, "hasSpecificRegulation", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bibliography_entries` (
                    `id` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `resourceType` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `authorsJson` TEXT,
                    `publicationYear` INTEGER,
                    `reference` TEXT,
                    `editor` TEXT,
                    `status` TEXT,
                    `scale` TEXT,
                    `detailUrl` TEXT,
                    `url` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_bibliography` (
                    `canyonId` INTEGER NOT NULL,
                    `bibliographyId` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `bibliographyId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `regulation_texts` (
                    `id` INTEGER NOT NULL,
                    `status` TEXT,
                    `action` TEXT,
                    `title` TEXT NOT NULL,
                    `summary` TEXT,
                    `remark` TEXT,
                    `details` TEXT,
                    `effectiveDate` TEXT,
                    `textUrl` TEXT,
                    `attachmentsJson` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canyon_regulations` (
                    `canyonId` INTEGER NOT NULL,
                    `regulationId` INTEGER NOT NULL,
                    PRIMARY KEY(`canyonId`, `regulationId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `app_metadata` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "isForbidden", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_5)
            .build()
    }

    @Provides
    fun provideCanyonDao(database: AppDatabase): CanyonDao = database.canyonDao()

    @Provides
    fun provideGeoPointDao(database: AppDatabase): GeoPointDao = database.geoPointDao()

    @Provides
    fun provideDebitDao(database: AppDatabase): DebitDao = database.debitDao()

    @Provides
    fun providePhotoDao(database: AppDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideBibliographyDao(database: AppDatabase): BibliographyDao = database.bibliographyDao()

    @Provides
    fun provideRegulationDao(database: AppDatabase): RegulationDao = database.regulationDao()

    @Provides
    fun provideAppMetadataDao(database: AppDatabase): AppMetadataDao = database.appMetadataDao()

    @Provides
    fun providePendingDebitSubmissionDao(database: AppDatabase): PendingDebitSubmissionDao {
        return database.pendingDebitSubmissionDao()
    }

    private fun ensureCanyonColumn(
        db: SupportSQLiteDatabase,
        columnName: String,
        sqlType: String,
    ) {
        if (!db.tableHasColumn("canyons", columnName)) {
            db.execSQL("ALTER TABLE canyons ADD COLUMN $columnName $sqlType")
        }
    }

    private fun SupportSQLiteDatabase.tableHasColumn(
        tableName: String,
        columnName: String,
    ): Boolean {
        val cursor: Cursor = query("PRAGMA table_info(`$tableName`)")
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (nameIndex >= 0 && it.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }
}
