package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.usecase.DownloadCanyonOfflineUseCase
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CanyonDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val favoritesRepository = mockk<FavoritesRepository>()
    private val photoRepository = mockk<PhotoRepository>()
    private val getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository)
    private val getCanyonDetailUseCase = GetCanyonDetailUseCase(canyonRepository)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
    private val downloadCanyonOfflineUseCase = DownloadCanyonOfflineUseCase(canyonRepository)
    private val downloadPhotoForOfflineUseCase = DownloadPhotoForOfflineUseCase(photoRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download for offline updates canyon state`() = runTest {
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail(isOffline = false))
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail(isOffline = false))
        coEvery { canyonRepository.downloadForOffline(42) } returns Result.success(Unit)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadCanyonOfflineUseCase = downloadCanyonOfflineUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            favoritesRepository = favoritesRepository,
        )
        advanceUntilIdle()

        viewModel.downloadForOffline()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canyonDetail?.canyon?.isOffline == true)
        assertFalse(viewModel.uiState.value.isDownloading)
        assertEquals("Canyon telecharge pour usage hors-ligne", viewModel.uiState.value.transientMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `preview loads before full detail`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail(isOffline = false).copy(canyon = detail(false).canyon.copy(nom = "Preview"))
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail(isOffline = false))
        coEvery { canyonRepository.downloadForOffline(42) } returns Result.success(Unit)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadCanyonOfflineUseCase = downloadCanyonOfflineUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download photo updates local path and transient message`() = runTest {
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail(false).copy(
                photos = listOf(
                    fr.descentecanyon.app.domain.model.CanyonPhoto(
                        id = 8,
                        canyonId = 42,
                        url = "https://example.com/photo.jpg",
                    )
                )
            )
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(
            detail(false).copy(
                photos = listOf(
                    fr.descentecanyon.app.domain.model.CanyonPhoto(
                        id = 8,
                        canyonId = 42,
                        url = "https://example.com/photo.jpg",
                    )
                )
            )
        )
        coEvery { canyonRepository.downloadForOffline(42) } returns Result.success(Unit)
        coEvery { photoRepository.downloadPhoto(8) } returns Result.success("/tmp/photo.jpg")
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)

        val viewModel = CanyonDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = getCanyonPreviewUseCase,
            getCanyonDetailUseCase = getCanyonDetailUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            downloadCanyonOfflineUseCase = downloadCanyonOfflineUseCase,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            favoritesRepository = favoritesRepository,
        )

        advanceUntilIdle()
        viewModel.downloadPhoto(8)
        advanceUntilIdle()

        assertEquals("/tmp/photo.jpg", viewModel.uiState.value.canyonDetail?.photos?.first()?.localPath)
        assertEquals("Photo telechargee", viewModel.uiState.value.transientMessage)
    }

    private fun detail(isOffline: Boolean) = CanyonDetail(
        canyon = Canyon(
            id = 42,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            commune = "Sigale",
            cotation = "v4a4III",
            url = "/canyoning/canyon/42/riolan.html",
            isOffline = isOffline,
        ),
    )
}
