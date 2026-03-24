package fr.descentecanyon.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.AppMetadataDao
import fr.descentecanyon.app.data.local.dao.BibliographyDao
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
            db.execSQL("ALTER TABLE debits ADD COLUMN isDescended INTEGER")
            db.execSQL("ALTER TABLE debits ADD COLUMN waterTemperature TEXT")
            db.execSQL("ALTER TABLE debits ADD COLUMN airTemperature TEXT")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE canyons ADD COLUMN communesJson TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN bassin TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN coursEau TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN geologie TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN historique TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN remarques TEXT")
            db.execSQL("ALTER TABLE canyons ADD COLUMN hasSpecificRegulation INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bibliography_entries` (
                    `id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `resourceType` TEXT,
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
                    `bibliographyId` TEXT NOT NULL,
                    PRIMARY KEY(`canyonId`, `bibliographyId`),
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`bibliographyId`) REFERENCES `bibliography_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_bibliography_canyonId` ON `canyon_bibliography` (`canyonId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_bibliography_bibliographyId` ON `canyon_bibliography` (`bibliographyId`)")

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
                    `textUrl` TEXT NOT NULL,
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
                    PRIMARY KEY(`canyonId`, `regulationId`),
                    FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`regulationId`) REFERENCES `regulation_texts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_regulations_canyonId` ON `canyon_regulations` (`canyonId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_canyon_regulations_regulationId` ON `canyon_regulations` (`regulationId`)")

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
}
