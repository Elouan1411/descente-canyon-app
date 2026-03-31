package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate

data class DebitPredictionSupport(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    val dailyWeather: List<DailyWeatherValue>,
    val runtimeLookup: ResolvedRuntimeFeatureLookup,
    val usedWeatherCache: Boolean,
)

data class DailyWeatherValue(
    val date: LocalDate,
    val precipitationSum: Double? = null,
    val rainSum: Double? = null,
    val snowfallSum: Double? = null,
    val temperature2mMean: Double? = null,
    val temperature2mMin: Double? = null,
    val temperature2mMax: Double? = null,
    val precipitationHours: Double? = null,
    val source: DailyWeatherSource,
)

enum class DailyWeatherSource {
    ARCHIVE,
    FORECAST,
}

enum class RuntimeLookupSource {
    CANYON,
    MASSIF,
    REGION,
    GLOBAL,
}

data class ResolvedRuntimeFeatureLookup(
    val canyonId: Int,
    val regionKey: String,
    val massifKey: String,
    val lookupSource: RuntimeLookupSource,
    val featureValues: Map<String, Double>,
)

data class DebitRuntimeLookups(
    val schemaVersion: Int,
    val labels: List<String>,
    val lookupFeatureNames: List<String>,
    val defaultRegionKey: String,
    val defaultMassifKey: String,
    val defaults: Map<String, Double>,
    val global: Map<String, Double>,
    val regions: Map<String, Map<String, Double>>,
    val massifs: Map<String, Map<String, Double>>,
    val canyons: Map<Int, RuntimeLookupEntry>,
)

data class RuntimeLookupEntry(
    val regionKey: String,
    val massifKey: String,
    val values: Map<String, Double>,
)

data class DebitFeatureSpec(
    val schemaVersion: Int,
    val labels: List<String>,
    val features: List<DebitFeatureDefinition>,
    val staticFeatureNames: List<String>,
    val lookupFeatureNames: List<String>,
    val dynamicFeatureNames: List<String>,
)

data class DebitFeatureDefinition(
    val name: String,
    val source: DebitFeatureSource,
    val defaultValue: Double,
)

enum class DebitFeatureSource {
    COMPUTED,
    STATIC,
    WEATHER,
    LOOKUP,
}

data class CanyonStaticFeatureSet(
    val canyonId: Int,
    val featureValues: Map<String, Double>,
)

enum class DebitPredictionPolicy {
    BALANCED,
    PRUDENT,
    SAFETY_FIRST,
}

enum class PredictedDebitLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class DebitThresholds(
    val defaultPolicy: DebitPredictionPolicy,
    val highThresholdByPolicy: Map<DebitPredictionPolicy, Double>,
)

data class DailyDebitPrediction(
    val date: LocalDate,
    val horizonDays: Int,
    val level: PredictedDebitLevel,
    val probabilities: Map<PredictedDebitLevel, Double>,
    val highThreshold: Double,
)

data class CanyonDebitPredictions(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    val lookupSource: RuntimeLookupSource,
    val usedWeatherCache: Boolean,
    val policy: DebitPredictionPolicy,
    val predictions: List<DailyDebitPrediction>,
)
