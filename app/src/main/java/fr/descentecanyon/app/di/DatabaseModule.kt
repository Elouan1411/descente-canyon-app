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
import fr.descentecanyon.app.data.local.dao.WatershedDao
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
            ensureTableColumn(db, "debits", "isDescended", "INTEGER")
            ensureTableColumn(db, "debits", "waterTemperature", "TEXT")
            ensureTableColumn(db, "debits", "airTemperature", "TEXT")
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

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureCanyonColumn(db, "isForbidden", "INTEGER NOT NULL DEFAULT 0")
            ensureTableColumn(db, "debits", "isDescended", "INTEGER")
            ensureTableColumn(db, "debits", "waterTemperature", "TEXT")
            ensureTableColumn(db, "debits", "airTemperature", "TEXT")
            recreateDebitsTable(db)

            db.execSQL("DROP TABLE IF EXISTS `canyon_bibliography`")
            db.execSQL("DROP TABLE IF EXISTS `bibliography_entries`")
            db.execSQL("DROP TABLE IF EXISTS `canyon_regulations`")
            db.execSQL("DROP TABLE IF EXISTS `regulation_texts`")
            db.execSQL("DROP TABLE IF EXISTS `app_metadata`")

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

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watersheds` (
                    `canyonId` INTEGER NOT NULL,
                    `areaKm2` REAL,
                    `geometryJson` TEXT,
                    `bboxMinLongitude` REAL,
                    `bboxMinLatitude` REAL,
                    `bboxMaxLongitude` REAL,
                    `bboxMaxLatitude` REAL,
                    PRIMARY KEY(`canyonId`)
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
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_7)
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
    fun provideWatershedDao(database: AppDatabase): WatershedDao = database.watershedDao()

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
        ensureTableColumn(db, "canyons", columnName, sqlType)
    }

    private fun ensureTableColumn(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        sqlType: String,
    ) {
        if (!db.tableHasColumn(tableName, columnName)) {
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $sqlType")
        }
    }

    private fun recreateDebitsTable(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debits` RENAME TO `debits_legacy`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `debits` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `canyonId` INTEGER NOT NULL,
                `date` TEXT NOT NULL,
                `niveau` TEXT NOT NULL,
                `auteur` TEXT,
                `isDescended` INTEGER,
                `waterTemperature` TEXT,
                `airTemperature` TEXT,
                `commentaire` TEXT,
                FOREIGN KEY(`canyonId`) REFERENCES `canyons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `debits` (`id`, `canyonId`, `date`, `niveau`, `auteur`, `isDescended`, `waterTemperature`, `airTemperature`, `commentaire`)
            SELECT `id`, `canyonId`, `date`, `niveau`, `auteur`, `isDescended`, `waterTemperature`, `airTemperature`, `commentaire`
            FROM `debits_legacy`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `debits_legacy`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_debits_canyonId` ON `debits` (`canyonId`)")
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
