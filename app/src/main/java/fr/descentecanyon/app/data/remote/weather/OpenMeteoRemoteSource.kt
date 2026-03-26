package fr.descentecanyon.app.data.remote.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class OpenMeteoRemoteSource @Inject constructor(
    private val httpClient: HttpClient,
) {

    open suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
    ): OpenMeteoForecastDto {
        return httpClient.get("/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter(
                "hourly",
                "precipitation,rain,showers,precipitation_probability,weather_code",
            )
            parameter("past_hours", 72)
            parameter("forecast_hours", 48)
            parameter("timezone", "auto")
        }.body()
    }
}
