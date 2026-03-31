package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CanyonDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val favoritesRepository = mockk<FavoritesRepository>()
    private val photoRepository = mockk<PhotoRepository>()
    private val debitRepository = mockk<DebitRepository>()
    private val debitPredictionRepository = mockk<DebitPredictionRepository>()
    private val weatherRepository = mockk<WeatherRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository)
    private val getCanyonDetailUseCase = GetCanyonDetailUseCase(canyonRepository)
    private val getCanyonWeatherUseCase = GetCanyonWeatherUseCase(weatherRepository)
    private val getCanyonDebitPredictionsUseCase = GetCanyonDebitPredictionsUseCase(debitPredictionRepository)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
    private val downloadPhotoForOfflineUseCase = DownloadPhotoForOfflineUseCase(photoRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `preview loads before full detail`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail().copy(canyon = detail().canyon.copy(nom = "Preview"))
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `full detail keeps bibliography and regulations after loading completes`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail().copy(canyon = detail().canyon.copy(nom = "Preview"))
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.canyonDetail?.bibliography?.size)
        assertEquals(1, viewModel.uiState.value.canyonDetail?.regulations?.size)
        assertNotNull(viewModel.uiState.value.weather)
        assertNotNull(viewModel.uiState.value.predictions)
        assertFalse(viewModel.uiState.value.isLoadingPhotos)
        assertFalse(viewModel.uiState.value.isLoadingDebits)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
        assertFalse(viewModel.uiState.value.isLoadingPredictions)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download photo updates local path and transient message`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        coEvery { photoRepository.downloadPhoto(8) } returns Result.success("/tmp/photo.jpg")
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )
        advanceUntilIdle()

        viewModel.downloadPhoto(8)
        advanceUntilIdle()

        assertEquals("/tmp/photo.jpg", viewModel.uiState.value.canyonDetail?.photos?.first()?.localPath)
        assertEquals("Photo téléchargée", viewModel.uiState.value.transientMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `weather failure keeps canyon detail available`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.failure(SocketTimeoutException("Request timeout has expired"))
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertEquals("Impossible de récupérer la météo pour le moment.", viewModel.uiState.value.weatherError)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `prediction timeout shows friendly message instead of raw timeout`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.failure(
            SocketTimeoutException("Request timeout has expired")
        )
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals(
            "Impossible de calculer l'estimation du débit pour le moment.",
            viewModel.uiState.value.predictionError,
        )
        assertFalse(viewModel.uiState.value.isLoadingPredictions)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `detail refresh failure keeps preview content available`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.failure(IllegalStateException("boom"))
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            canyonRepository = canyonRepository,
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertEquals("Impossible de charger cette fiche canyon pour le moment.", viewModel.uiState.value.transientMessage)
        assertEquals(null, viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.weather)
        assertNotNull(viewModel.uiState.value.predictions)
        assertFalse(viewModel.uiState.value.isRefreshingDetail)
    }

    private fun detail() = CanyonDetail(
        canyon = Canyon(
            id = 42,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            commune = "Sigale",
            cotation = "v4a4III",
            url = "/canyoning/canyon/42/riolan.html",
        ),
        photos = listOf(
            CanyonPhoto(
                id = 8,
                canyonId = 42,
                url = "https://example.test/photo.jpg",
                thumbnailUrl = "https://example.test/thumb.jpg",
            )
        ),
        bibliography = listOf(
            BibliographyEntry(
                id = "biblio-1",
                kind = BibliographyKind.RESOURCE,
                title = "Topo local",
            )
        ),
        regulations = listOf(
            Regulation(
                id = 7,
                status = "actif",
                title = "Arrete prefectoral",
                textUrl = "https://example.test/reglementation",
            )
        ),
        debits = listOf(
            Debit(
                canyonId = 42,
                date = java.time.LocalDate.of(2026, 3, 27),
                niveau = NiveauDebit.CORRECT,
                auteur = "alice",
            )
        ),
    )

    private fun weather() = CanyonWeather(
        target = WeatherTarget(
            latitude = 43.75,
            longitude = 6.25,
            source = WeatherLocationSource.WATERSHED_CENTER,
        ),
        timezone = "UTC",
        fetchedAt = Instant.parse("2026-03-26T12:00:00Z"),
        past24HoursPrecipitationMm = 12.0,
        past48HoursPrecipitationMm = 18.0,
        past72HoursPrecipitationMm = 24.0,
        next24HoursPrecipitationMm = 3.0,
        next48HoursPrecipitationMm = 6.0,
        maxHourlyPrecipitationPast72HoursMm = 4.5,
        maxPrecipitationProbabilityNext24Hours = 70,
    )

    private fun predictions() = CanyonDebitPredictions(
        target = WeatherTarget(
            latitude = 43.75,
            longitude = 6.25,
            source = WeatherLocationSource.WATERSHED_CENTER,
        ),
        timezone = "UTC",
        fetchedAt = Instant.parse("2026-03-26T12:00:00Z"),
        lookupSource = RuntimeLookupSource.CANYON,
        usedWeatherCache = false,
        policy = DebitPredictionPolicy.SAFETY_FIRST,
        predictions = listOf(
            DailyDebitPrediction(
                date = java.time.LocalDate.of(2026, 3, 26),
                horizonDays = 0,
                level = PredictedDebitLevel.MEDIUM,
                probabilities = mapOf(
                    PredictedDebitLevel.HIGH to 0.31,
                    PredictedDebitLevel.LOW to 0.18,
                    PredictedDebitLevel.MEDIUM to 0.51,
                ),
                highThreshold = 0.29,
            )
        ),
    )
}
