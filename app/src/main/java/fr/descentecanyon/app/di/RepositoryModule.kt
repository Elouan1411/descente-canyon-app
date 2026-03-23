package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.AuthRepositoryImpl
import fr.descentecanyon.app.data.repository.CanyonRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitSubmissionRepositoryImpl
import fr.descentecanyon.app.data.repository.FavoritesRepositoryImpl
import fr.descentecanyon.app.data.repository.MapOfflineRepositoryImpl
import fr.descentecanyon.app.data.repository.PhotoRepositoryImpl
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindCanyonRepository(impl: CanyonRepositoryImpl): CanyonRepository

    @Binds
    @Singleton
    abstract fun bindDebitRepository(impl: DebitRepositoryImpl): DebitRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindMapOfflineRepository(impl: MapOfflineRepositoryImpl): MapOfflineRepository

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(impl: PhotoRepositoryImpl): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindDebitSubmissionRepository(impl: DebitSubmissionRepositoryImpl): DebitSubmissionRepository
}
