package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class DebitPredictionRepositoryImpl @Inject constructor(
    private val supportRepository: DebitPredictionSupportRepository,
    private val modelStore: EmbeddedDebitModelStore,
    private val featureBuilder: DebitFeatureBuilder,
    private val predictor: OnnxDebitPredictor,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitPredictionRepository {

    override suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions> {
        return withContext(ioDispatcher) {
            runCatching {
                val support = supportRepository.getPredictionSupport(detail).getOrThrow()
                val featureSpec = modelStore.getFeatureSpec()
                val thresholds = modelStore.getThresholds()
                val staticFeatures = modelStore.getStaticFeatures()[detail.canyon.id]
                val zoneId = runCatching { ZoneId.of(support.timezone) }.getOrDefault(ZoneId.of("UTC"))
                val baseDate = LocalDate.now(zoneId)
                val predictions = (0..2).map { horizonDays ->
                    val targetDate = baseDate.plusDays(horizonDays.toLong())
                    val featureVector = featureBuilder.buildFeatureVector(
                        detail = detail,
                        support = support,
                        featureSpec = featureSpec,
                        staticFeatureSet = staticFeatures,
                        targetDate = targetDate,
                    )
                    predictor.predict(
                        featureSpec = featureSpec,
                        thresholds = thresholds,
                        featureVector = featureVector,
                        horizonDays = horizonDays,
                        date = targetDate,
                    )
                }
                CanyonDebitPredictions(
                    target = support.target,
                    timezone = support.timezone,
                    fetchedAt = support.fetchedAt,
                    lookupSource = support.runtimeLookup.lookupSource,
                    usedWeatherCache = support.usedWeatherCache,
                    policy = thresholds.defaultPolicy,
                    ordinalCutpoints = thresholds.ordinalCutpoints,
                    predictions = predictions,
                )
            }
        }
    }
}
