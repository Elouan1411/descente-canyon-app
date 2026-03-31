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
    private val sessionMutex = Mutex()

    @Volatile
    private var cachedFeatureSpec: DebitFeatureSpec? = null

    @Volatile
    private var cachedThresholds: DebitThresholds? = null

    @Volatile
    private var cachedStaticFeatures: Map<Int, CanyonStaticFeatureSet>? = null

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
            defaultPolicy = defaultPolicy.toPredictionPolicy(),
            highThresholdByPolicy = policies.mapKeys { (key, _) -> key.toPredictionPolicy() }
                .mapValues { (_, value) -> value.highThreshold },
        )
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
    val defaultPolicy: String,
    val policies: Map<String, ThresholdPolicyDto> = emptyMap(),
)

@Serializable
private data class ThresholdPolicyDto(
    val highThreshold: Double,
)
