package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.remote.weather.OpenMeteoDailyResponseDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DailyWeatherSource
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.DebitPredictionSupport
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class DebitPredictionSupportRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    private val dailyWeatherDao: DailyWeatherDao,
    private val runtimeLookupStore: EmbeddedDebitRuntimeLookupStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitPredictionSupportRepository {

    override suspend fun getPredictionSupport(detail: CanyonDetail): Result<DebitPredictionSupport> {
        return withContext(ioDispatcher) {
            runCatching {
                val target = WeatherTargetResolver.resolve(detail)
                    ?: throw IllegalStateException("Aucune coordonnee exploitable pour l'estimation du debit")
                val lookups = runtimeLookupStore.getLookups()
                val dailyWeather = loadDailyWeather(detail.canyon.id, target)
                DebitPredictionSupport(
                    target = target,
                    timezone = dailyWeather.timezone,
                    fetchedAt = dailyWeather.fetchedAt,
                    dailyWeather = dailyWeather.dailyWeather,
                    runtimeLookup = DebitRuntimeLookupResolver.resolve(detail.canyon, lookups),
                    usedWeatherCache = dailyWeather.usedCache,
                )
            }
        }
    }

    private suspend fun loadDailyWeather(canyonId: Int, target: WeatherTarget): DailyWeatherLoadResult {
        val forecastResult = runCatching {
            remoteSource.fetchDailyForecast(
                latitude = target.latitude,
                longitude = target.longitude,
                forecastDays = FORECAST_DAYS,
            )
        }
        if (forecastResult.isFailure) {
            return loadCachedFallback(canyonId)
                ?: throw forecastResult.exceptionOrNull() ?: IllegalStateException("Meteo journaliere indisponible")
        }

        val forecast = forecastResult.getOrThrow()
        val zoneId = forecast.timezone.toZoneIdOrUtc()
        val today = LocalDate.now(zoneId)
        val archiveStart = today.minusDays(LOOKBACK_DAYS)
        val archiveEnd = today.minusDays(1)

        val archiveRows = runCatching {
            remoteSource.fetchDailyArchive(
                latitude = target.latitude,
                longitude = target.longitude,
                startDate = archiveStart.toString(),
                endDate = archiveEnd.toString(),
            ).toDailyValues(DailyWeatherSource.ARCHIVE)
        }.getOrElse { throwable ->
            val cachedArchive = dailyWeatherDao.getByCanyonIdAndDateRange(
                canyonId = canyonId,
                startDate = archiveStart.toString(),
                endDate = archiveEnd.toString(),
            ).map { it.toDomain() }
            if (hasCompleteCoverage(cachedArchive, archiveStart, archiveEnd)) {
                return DailyWeatherLoadResult(
                    timezone = forecast.timezone,
                    fetchedAt = Instant.now(),
                    dailyWeather = (cachedArchive + forecast.toDailyValues(DailyWeatherSource.FORECAST)).sortedBy { it.date },
                    usedCache = true,
                )
            }
            throw throwable
        }

        val forecastRows = forecast.toDailyValues(DailyWeatherSource.FORECAST)
        val mergedRows = (archiveRows + forecastRows)
            .associateBy { it.date }
            .values
            .sortedBy { it.date }
        val fetchedAt = Instant.now()
        dailyWeatherDao.insertAll(
            mergedRows.map { row -> row.toEntity(canyonId, forecast.timezone, target, fetchedAt) }
        )
        dailyWeatherDao.deleteBefore(canyonId, today.minusDays(PRUNE_AFTER_DAYS).toString())

        return DailyWeatherLoadResult(
            timezone = forecast.timezone,
            fetchedAt = fetchedAt,
            dailyWeather = mergedRows,
            usedCache = false,
        )
    }

    private suspend fun loadCachedFallback(canyonId: Int): DailyWeatherLoadResult? {
        val today = LocalDate.now()
        val rows = dailyWeatherDao.getByCanyonIdAndDateRange(
            canyonId = canyonId,
            startDate = today.minusDays(LOOKBACK_DAYS + 2).toString(),
            endDate = today.plusDays(FORECAST_DAYS.toLong() - 1).toString(),
        )
        if (rows.isEmpty()) {
            return null
        }

        val dailyWeather = rows.map { it.toDomain() }
        val timezone = rows.first().timezone
        val zoneToday = LocalDate.now(timezone.toZoneIdOrUtc())
        if (!hasCompleteCoverage(dailyWeather, zoneToday.minusDays(LOOKBACK_DAYS), zoneToday.plusDays(FORECAST_DAYS.toLong() - 1))) {
            return null
        }

        return DailyWeatherLoadResult(
            timezone = timezone,
            fetchedAt = rows.maxOfOrNull { it.fetchedAtEpochMs }?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            dailyWeather = dailyWeather,
            usedCache = true,
        )
    }

    private fun OpenMeteoDailyResponseDto.toDailyValues(source: DailyWeatherSource): List<DailyWeatherValue> {
        val dailyRows = daily ?: return emptyList()
        return dailyRows.time.mapIndexed { index, rawDate ->
            DailyWeatherValue(
                date = LocalDate.parse(rawDate),
                precipitationSum = dailyRows.precipitationSum.getOrNull(index),
                rainSum = dailyRows.rainSum.getOrNull(index),
                snowfallSum = dailyRows.snowfallSum.getOrNull(index),
                temperature2mMean = dailyRows.temperature2mMean.getOrNull(index),
                temperature2mMin = dailyRows.temperature2mMin.getOrNull(index),
                temperature2mMax = dailyRows.temperature2mMax.getOrNull(index),
                precipitationHours = dailyRows.precipitationHours.getOrNull(index),
                source = source,
            )
        }
    }

    private fun DailyWeatherValue.toEntity(
        canyonId: Int,
        timezone: String,
        target: WeatherTarget,
        fetchedAt: Instant,
    ): DailyWeatherEntity {
        return DailyWeatherEntity(
            canyonId = canyonId,
            date = date.toString(),
            timezone = timezone,
            targetLatitude = target.latitude,
            targetLongitude = target.longitude,
            targetSource = target.source.name,
            sourceKind = source.name,
            precipitationSum = precipitationSum,
            rainSum = rainSum,
            snowfallSum = snowfallSum,
            temperature2mMean = temperature2mMean,
            temperature2mMin = temperature2mMin,
            temperature2mMax = temperature2mMax,
            precipitationHours = precipitationHours,
            fetchedAtEpochMs = fetchedAt.toEpochMilli(),
        )
    }

    private fun DailyWeatherEntity.toDomain(): DailyWeatherValue {
        return DailyWeatherValue(
            date = LocalDate.parse(date),
            precipitationSum = precipitationSum,
            rainSum = rainSum,
            snowfallSum = snowfallSum,
            temperature2mMean = temperature2mMean,
            temperature2mMin = temperature2mMin,
            temperature2mMax = temperature2mMax,
            precipitationHours = precipitationHours,
            source = DailyWeatherSource.valueOf(sourceKind),
        )
    }

    private fun hasCompleteCoverage(
        rows: List<DailyWeatherValue>,
        start: LocalDate,
        end: LocalDate,
    ): Boolean {
        var current = start
        val availableDates = rows.map { it.date }.toSet()
        while (!current.isAfter(end)) {
            if (current !in availableDates) {
                return false
            }
            current = current.plusDays(1)
        }
        return true
    }

    private fun String.toZoneIdOrUtc(): ZoneId {
        return runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))
    }

    private data class DailyWeatherLoadResult(
        val timezone: String,
        val fetchedAt: Instant,
        val dailyWeather: List<DailyWeatherValue>,
        val usedCache: Boolean,
    )

    companion object {
        private const val LOOKBACK_DAYS = 30L
        private const val FORECAST_DAYS = 3
        private const val PRUNE_AFTER_DAYS = 45L
    }
}
