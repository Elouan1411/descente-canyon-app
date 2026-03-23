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
import fr.descentecanyon.app.data.local.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
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
    fun providePendingDebitSubmissionDao(database: AppDatabase): PendingDebitSubmissionDao {
        return database.pendingDebitSubmissionDao()
    }
}
