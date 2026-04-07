package fr.descentecanyon.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EdfApiClient

@Module
@InstallIn(SingletonComponent::class)
object EdfModule {

    @EdfApiClient
    @Provides
    @Singleton
    fun provideEdfHttpClient(json: Json): HttpClient {
        return HttpClient(Android) {
            expectSuccess = true

            defaultRequest {
                url("https://mariviereetmoi.edf.fr")
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 25_000
            }
        }
    }
}
