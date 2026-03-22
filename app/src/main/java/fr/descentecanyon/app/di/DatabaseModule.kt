package fr.descentecanyon.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.DebitDao
import fr.descentecanyon.app.data.local.dao.GeoPointDao
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.local.database.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .fallbackToDestructiveMigration(true)
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
}
