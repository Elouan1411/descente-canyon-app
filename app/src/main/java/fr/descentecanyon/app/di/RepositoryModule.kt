package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.CanyonRepositoryImpl
import fr.descentecanyon.app.data.repository.DebitRepositoryImpl
import fr.descentecanyon.app.data.repository.FavoritesRepositoryImpl
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCanyonRepository(impl: CanyonRepositoryImpl): CanyonRepository

    @Binds
    @Singleton
    abstract fun bindDebitRepository(impl: DebitRepositoryImpl): DebitRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository
}
