package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnnxModelContractTest {

    @Test
    fun `model accepts exported feature count and exposes probability output`() {
        val modelPath = Paths.get("..", "modele_statistique", "model.onnx").normalize().toAbsolutePath().toString()
        val spec = exportedFeatureSpec()
        val featureCount = spec.featureCount
        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())

        session.use {
            val inputName = session.inputNames.single()
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
                    assertEquals(spec.labels.toSet(), probabilityMap!!.keys)
                }
            }
        }
    }

    @Test
    fun `model accepts batch dimension and exposes one probability row per input`() {
        val modelPath = Paths.get("..", "modele_statistique", "model.onnx").normalize().toAbsolutePath().toString()
        val spec = exportedFeatureSpec()
        val featureCount = spec.featureCount
        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())

        session.use {
            val inputName = session.inputNames.single()
            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(FloatArray(featureCount * 3)),
                longArrayOf(3, featureCount.toLong()),
            )
            tensor.use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { result ->
                    val probabilityRows = result.firstNotNullOfOrNull { (_, value) -> extractProbabilityRows(value) }
                    assertNotNull(probabilityRows)
                    assertEquals(3, probabilityRows!!.size)
                    probabilityRows.forEach { row ->
                        assertEquals(spec.labels.toSet(), row.keys)
                    }
                }
            }
        }
    }

    private fun exportedFeatureSpec(): ExportedFeatureSpec {
        val specPath = Paths.get("..", "modele_statistique", "feature_spec.json").normalize().toAbsolutePath()
        val payload = Files.readAllBytes(specPath).toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(payload).jsonObject
        return ExportedFeatureSpec(
            labels = root.getValue("labels").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull },
            featureCount = root.getValue("features").jsonArray.size,
        )
    }

    private data class ExportedFeatureSpec(
        val labels: List<String>,
        val featureCount: Int,
    )

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

    private fun extractProbabilityRows(value: ai.onnxruntime.OnnxValue): List<Map<String, Double>>? {
        return when (value) {
            is OnnxMap -> parseProbabilityMap(value.value)?.let(::listOf)
            is OnnxSequence -> extractProbabilityRowsFromRaw(value.value)
            is OnnxTensor -> when (val raw = value.value) {
                is Array<*> -> when (val first = raw.firstOrNull()) {
                    is FloatArray -> raw.mapNotNull { row ->
                        (row as? FloatArray)?.let { mapOf("HIGH" to it[0].toDouble(), "LOW" to it[1].toDouble(), "MEDIUM" to it[2].toDouble()) }
                    }.takeIf { it.isNotEmpty() }
                    is DoubleArray -> raw.mapNotNull { row ->
                        (row as? DoubleArray)?.let { mapOf("HIGH" to it[0], "LOW" to it[1], "MEDIUM" to it[2]) }
                    }.takeIf { it.isNotEmpty() }
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

    private fun extractProbabilityRowsFromRaw(raw: Any?): List<Map<String, Double>>? {
        return when (raw) {
            is OnnxMap -> parseProbabilityMap(raw.value)?.let(::listOf)
            is Map<*, *> -> parseProbabilityMap(raw)?.let(::listOf)
            is List<*> -> raw.mapNotNull { item -> extractProbabilityRowsFromRaw(item) }.flatten().takeIf { it.isNotEmpty() }
            is Array<*> -> raw.mapNotNull { item -> extractProbabilityRowsFromRaw(item) }.flatten().takeIf { it.isNotEmpty() }
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
