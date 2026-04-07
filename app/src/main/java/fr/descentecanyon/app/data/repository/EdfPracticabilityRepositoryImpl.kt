package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.edf.EdfDataPointDto
import fr.descentecanyon.app.data.remote.edf.EdfLimitDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityRemoteSource
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityCondition
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference
import fr.descentecanyon.app.domain.model.EdfPracticabilitySample
import fr.descentecanyon.app.domain.model.EdfPracticabilityThreshold
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.nio.channels.UnresolvedAddressException

@Singleton
class EdfPracticabilityRepositoryImpl @Inject constructor(
    private val remoteSource: EdfPracticabilityRemoteSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EdfPracticabilityRepository {

    override fun getReference(canyonId: Int): EdfPracticabilityReference? {
        return EdfPracticabilityMappings.getReference(canyonId)
    }

    override suspend fun getStatus(canyonId: Int): Result<CanyonEdfPracticability> {
        return withContext(ioDispatcher) {
            runCatching {
                val reference = getReference(canyonId)
                    ?: throw IllegalArgumentException("Aucune correspondance EDF pour le canyon $canyonId")
                retryRequest {
                    remoteSource.fetchPracticability(reference.practicabilityId)
                }.toDomain(reference)
            }
        }
    }

    private fun EdfPracticabilityDto.toDomain(reference: EdfPracticabilityReference): CanyonEdfPracticability {
        val chart = charts.firstOrNull { it.type == WATER_LEVEL_CHART && it.activity == CANYONING_ACTIVITY }
            ?: charts.firstOrNull { it.type == WATER_LEVEL_CHART }
        val lastSample = chart?.graph?.datas?.lastOrNull()?.toDomainSample()
        return CanyonEdfPracticability(
            practicabilityId = id,
            title = title,
            amenagementTitle = amenagement?.title.orEmpty(),
            sourceUrl = reference.sourceUrl,
            state = parseCondition(state).takeUnless { it == EdfPracticabilityCondition.UNKNOWN }
                ?: lastSample?.condition
                ?: EdfPracticabilityCondition.UNKNOWN,
            lastSample = lastSample,
            thresholds = chart?.graph?.limits.orEmpty().map { limit -> limit.toDomainThreshold() },
            description = description?.toPlainText(),
            hasPublishedEventInProgress = hasPublishedEventInProgress,
        )
    }

    private fun EdfDataPointDto.toDomainSample(): EdfPracticabilitySample? {
        val rawDateTime = dateTime ?: return null
        val instant = runCatching { Instant.parse(rawDateTime) }.getOrNull() ?: return null
        return EdfPracticabilitySample(
            value = value,
            recordedAt = instant,
            condition = parseCondition(condition),
        )
    }

    private fun EdfLimitDto.toDomainThreshold(): EdfPracticabilityThreshold {
        return EdfPracticabilityThreshold(
            condition = parseCondition(condition),
            min = min,
            max = max,
        )
    }

    private fun parseCondition(value: String?): EdfPracticabilityCondition {
        return when (value?.trim()?.uppercase()) {
            "APPROPRIATE" -> EdfPracticabilityCondition.APPROPRIATE
            "NOT_APPROPRIATE" -> EdfPracticabilityCondition.NOT_APPROPRIATE
            "NOT_INTERPRETED" -> EdfPracticabilityCondition.NOT_INTERPRETED
            else -> EdfPracticabilityCondition.UNKNOWN
        }
    }

    private fun String.toPlainText(): String {
        return Jsoup.parse(this)
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun <T> retryRequest(block: suspend () -> T): T {
        var lastFailure: Throwable? = null
        repeat(REQUEST_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (attempt == REQUEST_ATTEMPTS - 1 || !throwable.isRetryableFailure()) {
                    throw throwable
                }
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastFailure ?: IllegalStateException("EDF request failed")
    }

    private fun Throwable.isRetryableFailure(): Boolean {
        return generateSequence(this) { it.cause }.any { cause ->
            cause is UnknownHostException ||
                cause is UnresolvedAddressException ||
                cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause.message?.contains("timeout", ignoreCase = true) == true ||
                cause.message?.contains("timed out", ignoreCase = true) == true
        }
    }

    private companion object {
        const val WATER_LEVEL_CHART = "WATER_LEVEL"
        const val CANYONING_ACTIVITY = "CANYONING"
        const val REQUEST_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 600L
    }
}
