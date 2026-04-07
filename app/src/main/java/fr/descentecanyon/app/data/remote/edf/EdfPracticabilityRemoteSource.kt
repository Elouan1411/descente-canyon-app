package fr.descentecanyon.app.data.remote.edf

import fr.descentecanyon.app.di.EdfApiClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class EdfPracticabilityRemoteSource @Inject constructor(
    @param:EdfApiClient private val httpClient: HttpClient,
) {
    open suspend fun fetchPracticability(practicabilityId: Long): EdfPracticabilityDto {
        return httpClient.get("/api/v5/practicabilities/$practicabilityId").body()
    }
}
