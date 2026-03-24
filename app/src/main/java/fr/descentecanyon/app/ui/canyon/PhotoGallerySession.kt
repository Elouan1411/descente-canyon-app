package fr.descentecanyon.app.ui.canyon

import fr.descentecanyon.app.domain.model.CanyonPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PhotoGallerySession {
    private val _photos = MutableStateFlow<List<CanyonPhoto>>(emptyList())
    val photos: StateFlow<List<CanyonPhoto>> = _photos.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    fun open(photos: List<CanyonPhoto>, initialIndex: Int) {
        _photos.value = photos
        _currentIndex.value = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    }

    fun updateCurrentIndex(index: Int) {
        val max = (_photos.value.size - 1).coerceAtLeast(0)
        _currentIndex.value = index.coerceIn(0, max)
    }

    fun updatePhotoLocalPath(photoId: Long, localPath: String) {
        _photos.value = _photos.value.map { photo ->
            if (photo.id == photoId) photo.copy(localPath = localPath) else photo
        }
    }

    fun clear() {
        _photos.value = emptyList()
        _currentIndex.value = 0
    }
}
