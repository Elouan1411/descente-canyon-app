package fr.descentecanyon.app.ui.map

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import fr.descentecanyon.app.domain.usecase.DownloadMapOfflineRegionUseCase
import fr.descentecanyon.app.domain.usecase.GetNearbyCanyonsUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val mapOfflineRepository = mockk<MapOfflineRepository>()
    private val getNearbyCanyonsUseCase = GetNearbyCanyonsUseCase(canyonRepository)
    private val downloadMapOfflineRegionUseCase = DownloadMapOfflineRegionUseCase(mapOfflineRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `permission denial updates ui state`() = runTest {
        val viewModel = MapViewModel(getNearbyCanyonsUseCase, downloadMapOfflineRegionUseCase)

        viewModel.onLocationPermissionResult(false)

        assertFalse(viewModel.uiState.value.hasLocationPermission)
        assertTrue(viewModel.uiState.value.hasRequestedLocationPermission)
        assertEquals(
            "La position est necessaire pour charger les canyons proches.",
            viewModel.uiState.value.error,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `load nearby stores location and results`() = runTest {
        every { canyonRepository.getCanyonsNearby(43.7, 6.9, 50.0) } returns flowOf(
            Result.success(
                listOf(
                    CanyonSummary(
                        id = 42,
                        nom = "Riolan",
                        pays = "France",
                        cotation = "v4a4III",
                        url = "/canyoning/canyon/42/riolan.html",
                        latitude = 43.71,
                        longitude = 6.88,
                    )
                )
            )
        )
        val viewModel = MapViewModel(getNearbyCanyonsUseCase, downloadMapOfflineRegionUseCase)

        viewModel.loadNearby(43.7, 6.9)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(43.7, viewModel.uiState.value.userLatitude)
        assertEquals(6.9, viewModel.uiState.value.userLongitude)
        assertEquals(listOf(42), viewModel.uiState.value.canyons.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `select canyon exposes bottom sheet item`() = runTest {
        every { canyonRepository.getCanyonsNearby(43.7, 6.9, 50.0) } returns flowOf(
            Result.success(
                listOf(
                    CanyonSummary(
                        id = 7,
                        nom = "Aiglun",
                        pays = "France",
                        cotation = "v5a5IV",
                        url = "/canyoning/canyon/7/aiglun.html",
                    )
                )
            )
        )
        val viewModel = MapViewModel(getNearbyCanyonsUseCase, downloadMapOfflineRegionUseCase)

        viewModel.loadNearby(43.7, 6.9)
        advanceUntilIdle()
        viewModel.selectCanyon(7)

        assertEquals(7, viewModel.uiState.value.selectedCanyon?.id)
        viewModel.clearSelectedCanyon()
        assertEquals(null, viewModel.uiState.value.selectedCanyon)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download selected region exposes success message`() = runTest {
        every { canyonRepository.getCanyonsNearby(43.7, 6.9, 50.0) } returns flowOf(
            Result.success(
                listOf(
                    CanyonSummary(
                        id = 7,
                        nom = "Aiglun",
                        pays = "France",
                        cotation = "v5a5IV",
                        url = "/canyoning/canyon/7/aiglun.html",
                        latitude = 43.72,
                        longitude = 6.95,
                    )
                )
            )
        )
        coEvery { mapOfflineRepository.downloadRegion("Aiglun", 43.72, 6.95, 3.0) } returns Result.success(Unit)
        val viewModel = MapViewModel(getNearbyCanyonsUseCase, downloadMapOfflineRegionUseCase)

        viewModel.loadNearby(43.7, 6.9)
        advanceUntilIdle()
        viewModel.selectCanyon(7)
        viewModel.downloadSelectedRegion()
        advanceUntilIdle()

        assertEquals("Zone de carte telechargee pour Aiglun", viewModel.uiState.value.transientMessage)
        assertTrue(!viewModel.uiState.value.isDownloadingOfflineRegion)
    }
}
