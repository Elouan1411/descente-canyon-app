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
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class OnnxDebitPredictor @Inject constructor(
    private val modelStore: EmbeddedDebitModelStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
                    val ordinalLowThreshold = thresholds.lowThresholdByPolicy[policy]
                    val normalized = threeClassProbabilities(probabilitiesByLabel)
                    val ordinalScore = if (hasSixOrdinalLabels(probabilitiesByLabel)) {
                        expectedOrdinalScore(probabilitiesByLabel)
                    } else {
                        null
                    }
                    val ordinalStandardDeviation = ordinalScore?.let { score ->
                        ordinalStandardDeviation(probabilitiesByLabel, score)
                    }
                    val level = if (ordinalLowThreshold != null && ordinalScore != null) {
                        when {
                            ordinalScore >= highThreshold -> PredictedDebitLevel.HIGH
                            ordinalScore < ordinalLowThreshold -> PredictedDebitLevel.LOW
                            else -> PredictedDebitLevel.MEDIUM
                        }
                    } else {
                        when {
                            normalized.getValue(PredictedDebitLevel.HIGH) >= highThreshold -> PredictedDebitLevel.HIGH
                            normalized.getValue(PredictedDebitLevel.LOW) >= normalized.getValue(PredictedDebitLevel.MEDIUM) -> PredictedDebitLevel.LOW
                            else -> PredictedDebitLevel.MEDIUM
                        }
                    }
                    DailyDebitPrediction(
                        date = date,
                        horizonDays = horizonDays,
                        level = level,
                        probabilities = normalized,
                        highThreshold = highThreshold,
                        ordinalScore = ordinalScore,
                        ordinalLevel = ordinalScore?.let { score -> ordinalLevelFromScore(score, thresholds.ordinalCutpoints) },
                        ordinalStandardDeviation = ordinalStandardDeviation,
                    )
                }
            }
        }
    }

    private fun hasSixOrdinalLabels(probabilitiesByLabel: Map<String, Double>): Boolean {
        return ORDINAL_RANK_BY_LABEL.keys.all { label -> probabilitiesByLabel.containsKey(label) }
    }

    private fun expectedOrdinalScore(probabilitiesByLabel: Map<String, Double>): Double {
        return ORDINAL_RANK_BY_LABEL.entries.sumOf { (label, rank) ->
            (probabilitiesByLabel[label] ?: 0.0) * rank
        }
    }

    private fun ordinalStandardDeviation(
        probabilitiesByLabel: Map<String, Double>,
        score: Double,
    ): Double {
        val variance = ORDINAL_RANK_BY_LABEL.entries.sumOf { (label, rank) ->
            val distance = rank - score
            (probabilitiesByLabel[label] ?: 0.0) * distance * distance
        }
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun ordinalLevelFromScore(score: Double, cutpoints: List<Double>): NiveauDebit {
        if (cutpoints.size == 5 && cutpoints.zipWithNext().all { (left, right) -> left < right }) {
            val index = cutpoints.indexOfFirst { score < it }.takeIf { it >= 0 } ?: 5
            return when (index.coerceIn(0, 5)) {
                0 -> NiveauDebit.SEC
                1 -> NiveauDebit.FILET
                2 -> NiveauDebit.CORRECT
                3 -> NiveauDebit.GROS
                4 -> NiveauDebit.TRES_GROS
                else -> NiveauDebit.CRUE
            }
        }
        return when (score.roundToInt().coerceIn(0, 5)) {
            0 -> NiveauDebit.SEC
            1 -> NiveauDebit.FILET
            2 -> NiveauDebit.CORRECT
            3 -> NiveauDebit.GROS
            4 -> NiveauDebit.TRES_GROS
            else -> NiveauDebit.CRUE
        }
    }

    private fun threeClassProbabilities(probabilitiesByLabel: Map<String, Double>): Map<PredictedDebitLevel, Double> {
        return if (hasSixOrdinalLabels(probabilitiesByLabel)) {
            mapOf(
                PredictedDebitLevel.LOW to ((probabilitiesByLabel["SEC"] ?: 0.0) + (probabilitiesByLabel["FILET"] ?: 0.0)),
                PredictedDebitLevel.MEDIUM to (probabilitiesByLabel["CORRECT"] ?: 0.0),
                PredictedDebitLevel.HIGH to ((probabilitiesByLabel["GROS"] ?: 0.0) + (probabilitiesByLabel["TRES_GROS"] ?: 0.0) + (probabilitiesByLabel["CRUE"] ?: 0.0)),
            )
        } else {
            mapOf(
                PredictedDebitLevel.HIGH to (probabilitiesByLabel["HIGH"] ?: 0.0),
                PredictedDebitLevel.LOW to (probabilitiesByLabel["LOW"] ?: 0.0),
                PredictedDebitLevel.MEDIUM to (probabilitiesByLabel["MEDIUM"] ?: 0.0),
            )
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

    companion object {
        private val ORDINAL_RANK_BY_LABEL = mapOf(
            "SEC" to 0.0,
            "FILET" to 1.0,
            "CORRECT" to 2.0,
            "GROS" to 3.0,
            "TRES_GROS" to 4.0,
            "CRUE" to 5.0,
        )
    }
}
