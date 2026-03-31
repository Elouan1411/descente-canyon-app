package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnnxModelContractTest {

    @Test
    fun `model accepts 83 features and exposes probability output`() {
        val modelPath = Paths.get("..", "modele_statistique", "model.onnx").normalize().toAbsolutePath().toString()
        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())

        session.use {
            val inputName = session.inputNames.single()
            val featureCount = 83
            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(FloatArray(featureCount)),
                longArrayOf(1, featureCount.toLong()),
            )
            tensor.use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { result ->
                    assertFalse(!result.iterator().hasNext())
                    val probabilityMap = result.firstNotNullOfOrNull { (_, value) -> extractProbabilities(value) }
                    assertNotNull(probabilityMap)
                    assertEquals(setOf("HIGH", "LOW", "MEDIUM"), probabilityMap!!.keys)
                }
            }
        }
    }

    private fun extractProbabilities(value: ai.onnxruntime.OnnxValue): Map<String, Double>? {
        return when (value) {
            is OnnxMap -> parseProbabilityMap(value.value)
            is OnnxSequence -> extractProbabilitiesFromRaw(value.value)
            is OnnxTensor -> when (val raw = value.value) {
                is Array<*> -> when (val first = raw.firstOrNull()) {
                    is FloatArray -> mapOf("HIGH" to first[0].toDouble(), "LOW" to first[1].toDouble(), "MEDIUM" to first[2].toDouble())
                    is DoubleArray -> mapOf("HIGH" to first[0], "LOW" to first[1], "MEDIUM" to first[2])
                    else -> null
                }
                else -> null
            }
            else -> null
        }
    }

    private fun extractProbabilitiesFromRaw(raw: Any?): Map<String, Double>? {
        return when (raw) {
            is OnnxMap -> parseProbabilityMap(raw.value)
            is Map<*, *> -> parseProbabilityMap(raw)
            is List<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item) }
            is Array<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item) }
            else -> null
        }
    }

    private fun parseProbabilityMap(raw: Map<*, *>): Map<String, Double>? {
        val values = raw.entries.mapNotNull { entry ->
            val key = entry.key?.toString() ?: return@mapNotNull null
            val value = (entry.value as? Number)?.toDouble() ?: return@mapNotNull null
            key to value
        }.toMap()
        return values.takeIf { it.isNotEmpty() }
    }
}
