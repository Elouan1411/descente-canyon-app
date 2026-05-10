package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonStaticFeatureSet
import fr.descentecanyon.app.domain.model.DebitFeatureDefinition
import fr.descentecanyon.app.domain.model.DebitFeatureSource
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitPredictionDriver
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitThresholds
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class EmbeddedDebitModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val specMutex = Mutex()
    private val thresholdsMutex = Mutex()
    private val staticFeaturesMutex = Mutex()
    private val metricsMutex = Mutex()
    private val sessionMutex = Mutex()

    @Volatile
    private var cachedFeatureSpec: DebitFeatureSpec? = null

    @Volatile
    private var cachedThresholds: DebitThresholds? = null

    @Volatile
    private var cachedStaticFeatures: Map<Int, CanyonStaticFeatureSet>? = null

    @Volatile
    private var cachedMetricsSummary: DebitPredictionInfoSummary? = null

    @Volatile
    private var cachedSessionHolder: OnnxSessionHolder? = null

    suspend fun getFeatureSpec(): DebitFeatureSpec {
        cachedFeatureSpec?.let { return it }
        return specMutex.withLock {
            cachedFeatureSpec?.let { return it }
            loadJsonAsset<FeatureSpecDto>(FEATURE_SPEC_ASSET_PATH).toDomain().also { cachedFeatureSpec = it }
        }
    }

    suspend fun getThresholds(): DebitThresholds {
        cachedThresholds?.let { return it }
        return thresholdsMutex.withLock {
            cachedThresholds?.let { return it }
            loadJsonAsset<ThresholdsDto>(THRESHOLDS_ASSET_PATH).toDomain().also { cachedThresholds = it }
        }
    }

    suspend fun getStaticFeatures(): Map<Int, CanyonStaticFeatureSet> {
        cachedStaticFeatures?.let { return it }
        return staticFeaturesMutex.withLock {
            cachedStaticFeatures?.let { return it }
            val dto = loadJsonAsset<Map<String, JsonObject>>(STATIC_FEATURES_ASSET_PATH)
            dto.mapKeys { (key, _) -> key.toInt() }
                .mapValues { (key, value) -> CanyonStaticFeatureSet(key, value.toNumericMap()) }
                .also { cachedStaticFeatures = it }
        }
    }

    suspend fun getMetricsSummary(): DebitPredictionInfoSummary {
        cachedMetricsSummary?.let { return it }
        return metricsMutex.withLock {
            cachedMetricsSummary?.let { return it }
            loadJsonAsset<MetricsDto>(METRICS_ASSET_PATH).toDomain().also { cachedMetricsSummary = it }
        }
    }

    suspend fun getSession(): OnnxSessionHolder {
        cachedSessionHolder?.let { return it }
        return sessionMutex.withLock {
            cachedSessionHolder?.let { return it }
            withContext(ioDispatcher) {
                val modelFile = ensureModelFile()
                val environment = OrtEnvironment.getEnvironment()
                val session = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                OnnxSessionHolder(environment, session).also { cachedSessionHolder = it }
            }
        }
    }

    private suspend inline fun <reified T> loadJsonAsset(path: String): T {
        val payload = withContext(ioDispatcher) {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        return json.decodeFromString(payload)
    }

    private fun ensureModelFile(): File {
        val targetDir = File(context.filesDir, "debit-model-cache")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = File(targetDir, "model-v${BuildConfig.VERSION_CODE}.onnx")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return targetFile
    }

    private fun JsonObject.toNumericMap(): Map<String, Double> {
        return entries.mapNotNull { (key, value) ->
            when (value) {
                JsonNull -> null
                else -> {
                    val primitive = value.jsonPrimitive
                    primitive.doubleOrNull?.let { key to it }
                        ?: primitive.booleanOrNull?.let { key to if (it) 1.0 else 0.0 }
                }
            }
        }.toMap()
    }

    private fun FeatureSpecDto.toDomain(): DebitFeatureSpec {
        return DebitFeatureSpec(
            schemaVersion = schemaVersion,
            labels = labels,
            features = features.map { feature ->
                DebitFeatureDefinition(
                    name = feature.name,
                    source = when (feature.source.lowercase()) {
                        "computed" -> DebitFeatureSource.COMPUTED
                        "static" -> DebitFeatureSource.STATIC
                        "weather" -> DebitFeatureSource.WEATHER
                        "lookup" -> DebitFeatureSource.LOOKUP
                        else -> error("Unknown feature source: ${feature.source}")
                    },
                    defaultValue = feature.default,
                )
            },
            staticFeatureNames = staticFeatureNames,
            lookupFeatureNames = lookupFeatureNames,
            dynamicFeatureNames = dynamicFeatureNames,
        )
    }

    private fun ThresholdsDto.toDomain(): DebitThresholds {
        return DebitThresholds(
            targetMode = targetMode,
            defaultPolicy = defaultPolicy.toPredictionPolicy(),
            highThresholdByPolicy = policies.mapKeys { (key, _) -> key.toPredictionPolicy() }
                .mapValues { (_, value) -> value.highThreshold },
            lowThresholdByPolicy = policies.mapNotNull { (key, value) ->
                value.lowThreshold?.let { key.toPredictionPolicy() to it }
            }.toMap(),
        )
    }

    private fun MetricsDto.toDomain(): DebitPredictionInfoSummary {
        return DebitPredictionInfoSummary(
            modelName = model,
            targetMode = targetMode,
            featureCount = featureCount,
            trainRowCount = trainRowCount,
            calibrationRowCount = calibrationRowCount,
            testRowCount = testRowCount,
            canyonCount = runtimeLookupMetadata?.canyonCount ?: 0,
            regionCount = runtimeLookupMetadata?.regionCount ?: 0,
            massifCount = runtimeLookupMetadata?.massifCount ?: 0,
            topDrivers = topFeatureImportances
                .mapNotNull { feature -> feature.feature.toDriverOrNull() }
                .distinctBy { it.title }
                .take(5),
        )
    }

    private fun String.toDriverOrNull(): DebitPredictionDriver? {
        return when {
            startsWith("canyonPrior") || this == "canyonPastObsCount" -> {
                DebitPredictionDriver(
                    title = "Observations passées du canyon",
                    description = "Quand des observations passées existent pour ce canyon, elles aident le modèle à mieux situer son comportement habituel.",
                )
            }
            startsWith("massifPrior") || startsWith("regionPrior") || startsWith("globalPrior") ||
                contains("PastObsCount") -> {
                DebitPredictionDriver(
                    title = "Contexte du massif et de la région",
                    description = "Si le canyon manque d'observations propres, le modèle s'appuie davantage sur des canyons proches ou similaires.",
                )
            }
            startsWith("precip_") || startsWith("wet_days_") || startsWith("days_since_precip") ||
                startsWith("antecedent_precipitation_index") || startsWith("precipitation_hours") -> {
                DebitPredictionDriver(
                    title = "Pluie récente et cumuls",
                    description = "Les précipitations des derniers jours et des dernières semaines comptent beaucoup dans le niveau estimé.",
                )
            }
            startsWith("temperature") || startsWith("positive_degree_days") || startsWith("snowfall") || startsWith("rain") -> {
                DebitPredictionDriver(
                    title = "Température, pluie et neige",
                    description = "La température moyenne, la pluie liquide et la neige récente aident à distinguer des situations hydrologiques différentes.",
                )
            }
            this == "upstreamCatchmentAreaKm2" || this == "hasWatershed" -> {
                DebitPredictionDriver(
                    title = "Bassin versant",
                    description = "La surface du bassin versant aide à relier les pluies reçues à la réponse probable du canyon.",
                )
            }
            startsWith("month") -> {
                DebitPredictionDriver(
                    title = "Saison",
                    description = "La période de l'année influence le comportement moyen du canyon, notamment via la fonte et les régimes de pluie.",
                )
            }
            startsWith("historical") -> {
                DebitPredictionDriver(
                    title = "Signaux historiques atypiques",
                    description = "Certains canyons réagissent différemment à cause d'effets régulés ou de signatures de fonte observés dans les données passées.",
                )
            }
            else -> null
        }
    }

    private fun String.toPredictionPolicy(): DebitPredictionPolicy {
        return when (lowercase()) {
            "balanced" -> DebitPredictionPolicy.BALANCED
            "prudent" -> DebitPredictionPolicy.PRUDENT
            "safety_first" -> DebitPredictionPolicy.SAFETY_FIRST
            else -> error("Unknown threshold policy: $this")
        }
    }

    companion object {
        private const val FEATURE_SPEC_ASSET_PATH = "feature_spec.json"
        private const val THRESHOLDS_ASSET_PATH = "thresholds.json"
        private const val STATIC_FEATURES_ASSET_PATH = "canyon_static_features.json"
        private const val METRICS_ASSET_PATH = "metrics.json"
        private const val MODEL_ASSET_PATH = "model.onnx"
    }
}

data class OnnxSessionHolder(
    val environment: OrtEnvironment,
    val session: OrtSession,
)

@Serializable
private data class FeatureSpecDto(
    val schemaVersion: Int,
    val labels: List<String> = emptyList(),
    val features: List<FeatureDto> = emptyList(),
    val staticFeatureNames: List<String> = emptyList(),
    val lookupFeatureNames: List<String> = emptyList(),
    val dynamicFeatureNames: List<String> = emptyList(),
)

@Serializable
private data class FeatureDto(
    val name: String,
    val source: String,
    val default: Double,
)

@Serializable
private data class ThresholdsDto(
    val targetMode: String = "three",
    val defaultPolicy: String,
    val policies: Map<String, ThresholdPolicyDto> = emptyMap(),
)

@Serializable
private data class ThresholdPolicyDto(
    val highThreshold: Double,
    val lowThreshold: Double? = null,
)

@Serializable
private data class MetricsDto(
    val model: String,
    val targetMode: String,
    val featureCount: Int,
    val trainRowCount: Int,
    val calibrationRowCount: Int,
    val testRowCount: Int,
    val topFeatureImportances: List<FeatureImportanceDto> = emptyList(),
    val runtimeLookupMetadata: RuntimeLookupMetadataDto? = null,
)

@Serializable
private data class FeatureImportanceDto(
    val feature: String,
)

@Serializable
private data class RuntimeLookupMetadataDto(
    val regionCount: Int,
    val massifCount: Int,
    val canyonCount: Int,
)
