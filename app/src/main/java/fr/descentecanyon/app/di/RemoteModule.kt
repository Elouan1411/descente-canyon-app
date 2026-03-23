package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSourceImpl
import fr.descentecanyon.app.data.remote.scraper.MapIndexRemoteSource
import fr.descentecanyon.app.data.remote.scraper.MapIndexRemoteSourceImpl
import fr.descentecanyon.app.data.remote.scraper.NearbyCanyonRemoteSource
import fr.descentecanyon.app.data.remote.scraper.NearbyCanyonRemoteSourceImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {

    @Binds
    @Singleton
    abstract fun bindDebitSubmissionRemoteSource(
        impl: DebitSubmissionRemoteSourceImpl,
    ): DebitSubmissionRemoteSource

    @Binds
    @Singleton
    abstract fun bindNearbyCanyonRemoteSource(
        impl: NearbyCanyonRemoteSourceImpl,
    ): NearbyCanyonRemoteSource

    @Binds
    @Singleton
    abstract fun bindMapIndexRemoteSource(
        impl: MapIndexRemoteSourceImpl,
    ): MapIndexRemoteSource
}
