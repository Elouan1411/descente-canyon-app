package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.DebitRuntimeLookups
import fr.descentecanyon.app.domain.model.RuntimeLookupEntry
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class EmbeddedDebitRuntimeLookupStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val loadMutex = Mutex()

    @Volatile
    private var cached: DebitRuntimeLookups? = null

    suspend fun getLookups(): DebitRuntimeLookups {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return it }
            val payload = withContext(ioDispatcher) {
                context.assets.open(RUNTIME_LOOKUPS_ASSET_PATH).bufferedReader().use { it.readText() }
            }
            val dto = json.decodeFromString(RuntimeLookupsDto.serializer(), payload)
            dto.toDomain().also { cached = it }
        }
    }

    private fun RuntimeLookupsDto.toDomain(): DebitRuntimeLookups {
        return DebitRuntimeLookups(
            schemaVersion = schemaVersion,
            labels = labels,
            lookupFeatureNames = lookupFeatureNames,
            defaultRegionKey = unknownKeys.region,
            defaultMassifKey = unknownKeys.massif,
            defaults = defaults.toNumericMap(),
            global = global.toNumericMap(),
            regions = regions.mapValues { (_, value) -> value.toNumericMap() },
            massifs = massifs.mapValues { (_, value) -> value.toNumericMap() },
            canyons = canyons.mapKeys { (key, _) -> key.toInt() }.mapValues { (_, value) ->
                RuntimeLookupEntry(
                    regionKey = value["regionKey"]?.jsonPrimitive?.content ?: unknownKeys.region,
                    massifKey = value["massifKey"]?.jsonPrimitive?.content ?: unknownKeys.massif,
                    values = value.toNumericMap(),
                )
            },
        )
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

    companion object {
        private const val RUNTIME_LOOKUPS_ASSET_PATH = "runtime_feature_lookups.json"
    }
}

@Serializable
private data class RuntimeLookupsDto(
    val schemaVersion: Int,
    val labels: List<String> = emptyList(),
    val unknownKeys: UnknownKeysDto,
    val lookupFeatureNames: List<String> = emptyList(),
    val defaults: JsonObject = JsonObject(emptyMap()),
    val global: JsonObject = JsonObject(emptyMap()),
    val regions: Map<String, JsonObject> = emptyMap(),
    val massifs: Map<String, JsonObject> = emptyMap(),
    val canyons: Map<String, JsonObject> = emptyMap(),
)

@Serializable
private data class UnknownKeysDto(
    val region: String,
    val massif: String,
)
