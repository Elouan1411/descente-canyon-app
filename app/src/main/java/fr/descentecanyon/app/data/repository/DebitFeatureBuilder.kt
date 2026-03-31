package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonStaticFeatureSet
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitPredictionSupport
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebitFeatureBuilder @Inject constructor() {

    fun buildFeatureVector(
        detail: CanyonDetail,
        support: DebitPredictionSupport,
        featureSpec: DebitFeatureSpec,
        staticFeatureSet: CanyonStaticFeatureSet?,
        targetDate: LocalDate,
    ): FloatArray {
        val featureValues = mutableMapOf<String, Double?>()
        featureValues += computedTemporalFeatures(targetDate)
        featureValues += staticFeatures(detail, staticFeatureSet)
        featureValues += weatherFeatures(support.dailyWeather, targetDate)
        featureValues += support.runtimeLookup.featureValues

        return featureSpec.features.map { feature ->
            ((featureValues[feature.name] ?: feature.defaultValue).toFloat())
        }.toFloatArray()
    }

    private fun computedTemporalFeatures(targetDate: LocalDate): Map<String, Double> {
        val month = targetDate.monthValue.toDouble()
        val angle = 2.0 * PI * (targetDate.monthValue - 1).toDouble() / 12.0
        return mapOf(
            "month" to month,
            "monthSin" to roundTo(sin(angle), 6),
            "monthCos" to roundTo(cos(angle), 6),
        )
    }

    private fun staticFeatures(
        detail: CanyonDetail,
        staticFeatureSet: CanyonStaticFeatureSet?,
    ): Map<String, Double?> {
        if (staticFeatureSet != null) {
            return staticFeatureSet.featureValues.mapValues { (_, value) -> value }
        }

        return mapOf(
            "altitudeDepartM" to detail.canyon.altitudeDepart?.toDouble(),
            "deniveleM" to detail.canyon.denivele?.toDouble(),
            "longueurM" to detail.canyon.longueur?.toDouble(),
            "cascadeMaxM" to detail.canyon.cascadeMax?.toDouble(),
            "upstreamCatchmentAreaKm2" to detail.watershed?.areaKm2,
            "hasWatershed" to if (detail.watershed != null) 1.0 else 0.0,
        )
    }

    private fun weatherFeatures(
        dailyWeather: List<DailyWeatherValue>,
        targetDate: LocalDate,
    ): Map<String, Double?> {
        val features = mutableMapOf<String, Double?>(
            "precip_prev_day_mm" to null,
            "precip_2d_mm" to 0.0,
            "precip_3d_mm" to 0.0,
            "precip_5d_mm" to 0.0,
            "precip_7d_mm" to 0.0,
            "precip_10d_mm" to 0.0,
            "precip_14d_mm" to 0.0,
            "precip_21d_mm" to 0.0,
            "precip_30d_mm" to 0.0,
            "max_daily_precip_3d_mm" to 0.0,
            "max_daily_precip_7d_mm" to 0.0,
            "max_daily_precip_14d_mm" to 0.0,
            "wet_days_7d" to 0.0,
            "wet_days_14d" to 0.0,
            "wet_days_30d" to 0.0,
            "days_since_precip_over_1mm" to null,
            "days_since_precip_over_5mm" to null,
            "days_since_precip_over_10mm" to null,
            "antecedent_precipitation_index_daily" to 0.0,
            "antecedent_precipitation_index_daily_70" to 0.0,
            "antecedent_precipitation_index_daily_85" to 0.0,
            "antecedent_precipitation_index_daily_93" to 0.0,
            "rain_prev_day_mm" to null,
            "rain_3d_mm" to 0.0,
            "rain_7d_mm" to 0.0,
            "snowfall_prev_day_cm" to null,
            "snowfall_3d_cm" to 0.0,
            "snowfall_7d_cm" to 0.0,
            "snowfall_14d_cm" to 0.0,
            "temperature2mMeanPrevDay" to null,
            "temperature2mMinPrevDay" to null,
            "temperature2mMaxPrevDay" to null,
            "temperature2mMean_3d" to null,
            "temperature2mMean_7d" to null,
            "temperature2mMean_14d" to null,
            "positive_degree_days_3d" to 0.0,
            "positive_degree_days_7d" to 0.0,
            "positive_degree_days_14d" to 0.0,
            "precipitation_hours_3d" to 0.0,
            "precipitation_hours_7d" to 0.0,
            "precipitation_hours_14d" to 0.0,
            "temperature2mAtObservation" to null,
            "temperature2mMinAtObservationDay" to null,
            "temperature2mMaxAtObservationDay" to null,
            "rainAtObservationDay" to null,
            "snowfallAtObservationDay" to null,
            "precipitationHoursAtObservationDay" to null,
        )

        val eligibleRows = dailyWeather.sortedBy { it.date }.filter { it.date < targetDate }
        if (eligibleRows.isEmpty()) {
            return features
        }

        val precipByDay = eligibleRows.map { it.precipitationSum ?: 0.0 }
        val rainByDay = eligibleRows.map { it.rainSum ?: 0.0 }
        val snowfallByDay = eligibleRows.map { it.snowfallSum ?: 0.0 }
        val temperatureMeanByDay = eligibleRows.map { it.temperature2mMean }
        val temperatureMinByDay = eligibleRows.map { it.temperature2mMin }
        val temperatureMaxByDay = eligibleRows.map { it.temperature2mMax }
        val precipitationHoursByDay = eligibleRows.map { it.precipitationHours ?: 0.0 }

        fun trailingSum(values: List<Double>, days: Int): Double = roundTo(values.takeLast(days).sum(), 3)
        fun trailingMax(values: List<Double>, days: Int): Double = roundTo(values.takeLast(days).maxOrNull() ?: 0.0, 3)
        fun trailingCount(values: List<Double>, days: Int, threshold: Double): Double = values.takeLast(days).count { it >= threshold }.toDouble()
        fun trailingMean(values: List<Double?>, days: Int): Double? {
            val trailing = values.takeLast(days).filterNotNull()
            return if (trailing.isEmpty()) null else roundTo(trailing.average(), 3)
        }
        fun trailingPositiveDegreeDays(values: List<Double?>, days: Int): Double {
            return roundTo(values.takeLast(days).filterNotNull().sumOf { max(it, 0.0) }, 3)
        }

        features["precip_prev_day_mm"] = roundTo(precipByDay.last(), 3)
        features["precip_2d_mm"] = trailingSum(precipByDay, 2)
        features["precip_3d_mm"] = trailingSum(precipByDay, 3)
        features["precip_5d_mm"] = trailingSum(precipByDay, 5)
        features["precip_7d_mm"] = trailingSum(precipByDay, 7)
        features["precip_10d_mm"] = trailingSum(precipByDay, 10)
        features["precip_14d_mm"] = trailingSum(precipByDay, 14)
        features["precip_21d_mm"] = trailingSum(precipByDay, 21)
        features["precip_30d_mm"] = trailingSum(precipByDay, 30)
        features["max_daily_precip_3d_mm"] = trailingMax(precipByDay, 3)
        features["max_daily_precip_7d_mm"] = trailingMax(precipByDay, 7)
        features["max_daily_precip_14d_mm"] = trailingMax(precipByDay, 14)
        features["wet_days_7d"] = trailingCount(precipByDay, 7, 0.1)
        features["wet_days_14d"] = trailingCount(precipByDay, 14, 0.1)
        features["wet_days_30d"] = trailingCount(precipByDay, 30, 0.1)

        val previousDayRow = eligibleRows.last()
        features["rain_prev_day_mm"] = previousDayRow.rainSum?.let { roundTo(it, 3) }
        features["snowfall_prev_day_cm"] = previousDayRow.snowfallSum?.let { roundTo(it, 3) }
        features["temperature2mMeanPrevDay"] = previousDayRow.temperature2mMean
        features["temperature2mMinPrevDay"] = previousDayRow.temperature2mMin
        features["temperature2mMaxPrevDay"] = previousDayRow.temperature2mMax
        features["rain_3d_mm"] = trailingSum(rainByDay, 3)
        features["rain_7d_mm"] = trailingSum(rainByDay, 7)
        features["snowfall_3d_cm"] = trailingSum(snowfallByDay, 3)
        features["snowfall_7d_cm"] = trailingSum(snowfallByDay, 7)
        features["snowfall_14d_cm"] = trailingSum(snowfallByDay, 14)
        features["temperature2mMean_3d"] = trailingMean(temperatureMeanByDay, 3)
        features["temperature2mMean_7d"] = trailingMean(temperatureMeanByDay, 7)
        features["temperature2mMean_14d"] = trailingMean(temperatureMeanByDay, 14)
        features["positive_degree_days_3d"] = trailingPositiveDegreeDays(temperatureMeanByDay, 3)
        features["positive_degree_days_7d"] = trailingPositiveDegreeDays(temperatureMeanByDay, 7)
        features["positive_degree_days_14d"] = trailingPositiveDegreeDays(temperatureMeanByDay, 14)
        features["precipitation_hours_3d"] = trailingSum(precipitationHoursByDay, 3)
        features["precipitation_hours_7d"] = trailingSum(precipitationHoursByDay, 7)
        features["precipitation_hours_14d"] = trailingSum(precipitationHoursByDay, 14)

        listOf(
            1.0 to "days_since_precip_over_1mm",
            5.0 to "days_since_precip_over_5mm",
            10.0 to "days_since_precip_over_10mm",
        ).forEach { (threshold, featureName) ->
            val lastMatchDay = eligibleRows.lastOrNull { (it.precipitationSum ?: 0.0) >= threshold }?.date
            features[featureName] = lastMatchDay?.let { targetDate.minusDays(1).toEpochDay() - it.toEpochDay() }?.toDouble()
        }

        var api70 = 0.0
        var api85 = 0.0
        var api93 = 0.0
        eligibleRows.takeLast(30).forEach { row ->
            val precipitation = row.precipitationSum ?: 0.0
            api70 = api70 * 0.70 + precipitation
            api85 = api85 * 0.85 + precipitation
            api93 = api93 * 0.93 + precipitation
        }
        features["antecedent_precipitation_index_daily_70"] = roundTo(api70, 3)
        features["antecedent_precipitation_index_daily_85"] = roundTo(api85, 3)
        features["antecedent_precipitation_index_daily_93"] = roundTo(api93, 3)
        features["antecedent_precipitation_index_daily"] = roundTo(api85, 3)

        features["temperature2mAtObservation"] = previousDayRow.temperature2mMean
        features["temperature2mMinAtObservationDay"] = previousDayRow.temperature2mMin
        features["temperature2mMaxAtObservationDay"] = previousDayRow.temperature2mMax
        features["rainAtObservationDay"] = previousDayRow.rainSum
        features["snowfallAtObservationDay"] = previousDayRow.snowfallSum
        features["precipitationHoursAtObservationDay"] = previousDayRow.precipitationHours

        return features
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return round(value * factor) / factor
    }
}
