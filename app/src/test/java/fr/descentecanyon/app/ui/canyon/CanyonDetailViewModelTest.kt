package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    private val weatherRepository = mockk<WeatherRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository)
    private val getCanyonDetailUseCase = GetCanyonDetailUseCase(canyonRepository)
    private val getCanyonWeatherUseCase = GetCanyonWeatherUseCase(weatherRepository)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
    private val downloadPhotoForOfflineUseCase = DownloadPhotoForOfflineUseCase(photoRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `preview loads before full detail`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail().copy(canyon = detail().canyon.copy(nom = "Preview"))
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
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
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.canyonDetail?.bibliography?.size)
        assertEquals(1, viewModel.uiState.value.canyonDetail?.regulations?.size)
        assertNotNull(viewModel.uiState.value.weather)
        assertFalse(viewModel.uiState.value.isLoadingPhotos)
        assertFalse(viewModel.uiState.value.isLoadingDebits)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download photo updates local path and transient message`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        coEvery { photoRepository.downloadPhoto(8) } returns Result.success("/tmp/photo.jpg")
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )
        advanceUntilIdle()

        viewModel.downloadPhoto(8)
        advanceUntilIdle()

        assertEquals("/tmp/photo.jpg", viewModel.uiState.value.canyonDetail?.photos?.first()?.localPath)
        assertEquals("Photo telechargee", viewModel.uiState.value.transientMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `weather failure keeps canyon detail available`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.failure(IllegalStateException("Meteo indisponible"))
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { connectivityObserver.observe() } returns flowOf(true)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertEquals("Meteo indisponible", viewModel.uiState.value.weatherError)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
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
}
