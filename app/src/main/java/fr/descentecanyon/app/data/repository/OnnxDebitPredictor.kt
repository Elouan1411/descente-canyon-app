package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitThresholds
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class OnnxDebitPredictor @Inject constructor(
    private val modelStore: EmbeddedDebitModelStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun predict(
        featureSpec: DebitFeatureSpec,
        thresholds: DebitThresholds,
        featureVector: FloatArray,
        horizonDays: Int,
        date: java.time.LocalDate,
        policy: DebitPredictionPolicy = thresholds.defaultPolicy,
    ): DailyDebitPrediction {
        return withContext(ioDispatcher) {
            val sessionHolder = modelStore.getSession()
            val inputName = sessionHolder.session.inputNames.firstOrNull()
                ?: error("No ONNX input found")
            val tensor = OnnxTensor.createTensor(
                sessionHolder.environment,
                FloatBuffer.wrap(featureVector),
                longArrayOf(1, featureVector.size.toLong()),
            )
            tensor.use { inputTensor ->
                sessionHolder.session.run(mapOf(inputName to inputTensor)).use { result ->
                    val probabilitiesByLabel = extractProbabilities(result, featureSpec.labels)
                    val highThreshold = thresholds.highThresholdByPolicy[policy]
                        ?: error("Missing threshold for policy $policy")
                    val normalized = mapOf(
                        PredictedDebitLevel.HIGH to (probabilitiesByLabel["HIGH"] ?: 0.0),
                        PredictedDebitLevel.LOW to (probabilitiesByLabel["LOW"] ?: 0.0),
                        PredictedDebitLevel.MEDIUM to (probabilitiesByLabel["MEDIUM"] ?: 0.0),
                    )
                    val level = when {
                        normalized.getValue(PredictedDebitLevel.HIGH) >= highThreshold -> PredictedDebitLevel.HIGH
                        normalized.getValue(PredictedDebitLevel.LOW) >= normalized.getValue(PredictedDebitLevel.MEDIUM) -> PredictedDebitLevel.LOW
                        else -> PredictedDebitLevel.MEDIUM
                    }
                    DailyDebitPrediction(
                        date = date,
                        horizonDays = horizonDays,
                        level = level,
                        probabilities = normalized,
                        highThreshold = highThreshold,
                    )
                }
            }
        }
    }

    private fun extractProbabilities(
        result: ai.onnxruntime.OrtSession.Result,
        labels: List<String>,
    ): Map<String, Double> {
        result.forEach { (_, value) ->
            extractProbabilities(value, labels)?.let { return it }
        }
        error("No probability output found in ONNX result")
    }

    private fun extractProbabilities(value: OnnxValue, labels: List<String>): Map<String, Double>? {
        return when (value) {
            is OnnxMap -> parseProbabilityMap(value.value)
            is OnnxSequence -> extractProbabilitiesFromRaw(value.value, labels)
            is OnnxTensor -> parseTensorProbabilities(value.value, labels)
            else -> null
        }
    }

    private fun extractProbabilitiesFromRaw(raw: Any?, labels: List<String>): Map<String, Double>? {
        return when (raw) {
            is OnnxMap -> parseProbabilityMap(raw.value)
            is Map<*, *> -> parseProbabilityMap(raw)
            is List<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item, labels) }
            is Array<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item, labels) }
            else -> null
        }
    }

    private fun parseProbabilityMap(raw: Map<*, *>): Map<String, Double>? {
        val probabilities = raw.entries.mapNotNull { entry ->
            val label = entry.key?.toString() ?: return@mapNotNull null
            val probability = (entry.value as? Number)?.toDouble() ?: return@mapNotNull null
            label to probability
        }.toMap()
        return probabilities.takeIf { it.isNotEmpty() }
    }

    private fun parseTensorProbabilities(raw: Any?, labels: List<String>): Map<String, Double>? {
        return when (raw) {
            is Array<*> -> when (val first = raw.firstOrNull()) {
                is FloatArray -> labels.zip(first.map { it.toDouble() }).toMap()
                is DoubleArray -> labels.zip(first.toList()).toMap()
                else -> null
            }
            is FloatArray -> labels.zip(raw.map { it.toDouble() }).toMap()
            is DoubleArray -> labels.zip(raw.toList()).toMap()
            else -> null
        }
    }
}
