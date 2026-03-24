package fr.descentecanyon.app.ui.canyon

import fr.descentecanyon.app.domain.model.CanyonPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PhotoGallerySession {
    private val _photos = MutableStateFlow<List<CanyonPhoto>>(emptyList())
    val photos: StateFlow<List<CanyonPhoto>> = _photos.asStateFlow()

    private val _initialIndex = MutableStateFlow(0)
    val initialIndex: StateFlow<Int> = _initialIndex.asStateFlow()

    fun open(photos: List<CanyonPhoto>, initialIndex: Int) {
        _photos.value = photos
        _initialIndex.value = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    }

    fun updatePhotoLocalPath(photoId: Long, localPath: String) {
        _photos.value = _photos.value.map { photo ->
            if (photo.id == photoId) photo.copy(localPath = localPath) else photo
        }
    }

    fun clear() {
        _photos.value = emptyList()
        _initialIndex.value = 0
    }
}
