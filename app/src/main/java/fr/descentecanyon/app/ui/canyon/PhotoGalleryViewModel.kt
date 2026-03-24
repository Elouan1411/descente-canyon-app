package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhotoGalleryUiState(
    val downloadingPhotoIds: Set<Long> = emptySet(),
)

@HiltViewModel
class PhotoGalleryViewModel @Inject constructor(
    private val downloadPhotoForOfflineUseCase: DownloadPhotoForOfflineUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    fun downloadPhoto(photoId: Long) {
        if (photoId == 0L || _uiState.value.downloadingPhotoIds.contains(photoId)) return

        viewModelScope.launch {
            _uiState.update { it.copy(downloadingPhotoIds = it.downloadingPhotoIds + photoId) }
            downloadPhotoForOfflineUseCase(photoId).onSuccess { localPath ->
                PhotoGallerySession.updatePhotoLocalPath(photoId, localPath)
            }
            _uiState.update { it.copy(downloadingPhotoIds = it.downloadingPhotoIds - photoId) }
        }
    }
}
