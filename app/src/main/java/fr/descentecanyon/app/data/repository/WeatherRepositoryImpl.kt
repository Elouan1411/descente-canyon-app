package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.HourlyPrecipitation
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.WeatherRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class WeatherRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WeatherRepository {

    override suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather> {
        return withContext(ioDispatcher) {
            runCatching {
                val target = resolveWeatherTarget(detail)
                    ?: throw IllegalStateException("Aucune coordonnee exploitable pour la meteo")
                val response = remoteSource.fetchForecast(target.latitude, target.longitude)
                val zoneId = response.timezone.toZoneIdOrUtc()
                val now = ZonedDateTime.now(zoneId)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)

                val hourly = response.hourly.toHourlyPrecipitation()
                if (hourly.isEmpty()) {
                    throw IllegalStateException("Aucune donnee de precipitation disponible")
                }

                val pastHours = hourly.filter { !it.dateTime.atZone(zoneId).isAfter(now) }
                val futureHours = hourly.filter { it.dateTime.atZone(zoneId).isAfter(now) }

                CanyonWeather(
                    target = target,
                    timezone = response.timezone,
                    fetchedAt = Instant.now(),
                    hourly = hourly,
                    past24HoursPrecipitationMm = pastHours.sumLast(24),
                    past48HoursPrecipitationMm = pastHours.sumLast(48),
                    past72HoursPrecipitationMm = pastHours.sumLast(72),
                    next24HoursPrecipitationMm = futureHours.sumFirst(24),
                    next48HoursPrecipitationMm = futureHours.sumFirst(48),
                    maxHourlyPrecipitationPast72HoursMm = pastHours.maxLast(72),
                    maxPrecipitationProbabilityNext24Hours = futureHours
                        .take(24)
                        .mapNotNull { it.precipitationProbabilityPercent }
                        .maxOrNull(),
                )
            }
        }
    }

    private fun resolveWeatherTarget(detail: CanyonDetail): WeatherTarget? {
        detail.watershed?.bounds?.let { bounds ->
            return WeatherTarget(
                latitude = bounds.centerLatitude(),
                longitude = bounds.centerLongitude(),
                source = WeatherLocationSource.WATERSHED_CENTER,
            )
        }

        return detail.geoPoints
            .minByOrNull(::weatherPriority)
            ?.toWeatherTarget()
    }

    private fun weatherPriority(point: GeoPoint): Int {
        return when (point.type) {
            GeoPointType.ENTREE -> 0
            GeoPointType.PARKING_AMONT -> 1
            GeoPointType.SORTIE -> 2
            GeoPointType.PARKING_AVAL -> 3
            GeoPointType.POINT_REMARQUABLE -> 4
            GeoPointType.ECHAPPATOIRE -> 5
            GeoPointType.UNKNOWN -> 6
        }
    }

    private fun GeoPoint.toWeatherTarget(): WeatherTarget {
        return WeatherTarget(
            latitude = latitude,
            longitude = longitude,
            source = when (type) {
                GeoPointType.ENTREE -> WeatherLocationSource.ENTRY
                GeoPointType.PARKING_AMONT -> WeatherLocationSource.UPSTREAM_PARKING
                GeoPointType.SORTIE -> WeatherLocationSource.EXIT
                GeoPointType.PARKING_AVAL -> WeatherLocationSource.DOWNSTREAM_PARKING
                GeoPointType.POINT_REMARQUABLE -> WeatherLocationSource.REMARKABLE_POINT
                GeoPointType.ECHAPPATOIRE -> WeatherLocationSource.ESCAPE
                GeoPointType.UNKNOWN -> WeatherLocationSource.UNKNOWN
            },
        )
    }

    private fun GeoBounds.centerLatitude(): Double = (minLatitude + maxLatitude) / 2.0

    private fun GeoBounds.centerLongitude(): Double = (minLongitude + maxLongitude) / 2.0

    private fun String.toZoneIdOrUtc(): ZoneId {
        return runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))
    }

    private fun fr.descentecanyon.app.data.remote.weather.HourlyForecastDto?.toHourlyPrecipitation(): List<HourlyPrecipitation> {
        if (this == null) return emptyList()

        return time.mapIndexedNotNull { index, rawTime ->
            val dateTime = runCatching { LocalDateTime.parse(rawTime) }.getOrNull() ?: return@mapIndexedNotNull null
            HourlyPrecipitation(
                dateTime = dateTime,
                precipitationMm = precipitation.getOrNull(index) ?: 0.0,
                rainMm = rain.getOrNull(index),
                showersMm = showers.getOrNull(index),
                precipitationProbabilityPercent = precipitationProbability.getOrNull(index),
                weatherCode = weatherCode.getOrNull(index),
            )
        }
    }

    private fun List<HourlyPrecipitation>.sumLast(hours: Int): Double {
        return takeLast(hours).sumOf { it.precipitationMm }
    }

    private fun List<HourlyPrecipitation>.sumFirst(hours: Int): Double {
        return take(hours).sumOf { it.precipitationMm }
    }

    private fun List<HourlyPrecipitation>.maxLast(hours: Int): Double {
        return takeLast(hours).maxOfOrNull { it.precipitationMm } ?: 0.0
    }
}
