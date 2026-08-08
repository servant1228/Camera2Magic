package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.MediaPathResolver
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SpotlightUiState(
    val moduleEnabled: Boolean = true,
    val xposedActive: Boolean = false,
    val isProcessing: Boolean = false,
    val selectedMediaSource: MediaSource = MediaSource.LOCAL,
    val currentType: MediaType = MediaType.VIDEO,
    val thumbnails: PersistentMap<MediaType, Bitmap?> = persistentMapOf(),
    val displayPaths: PersistentMap<MediaType, String?> = persistentMapOf(),
    val processingMedia: PersistentSet<MediaType> = persistentSetOf()
)

class SpotlightViewModel(
    private val app: Application,
    private val repository: ConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotlightUiState())
    val uiState = _uiState.asStateFlow()

    private fun updateState(reducer: (SpotlightUiState) -> SpotlightUiState) {
        _uiState.update { reducer(it) }
    }

    companion object {
        private const val TAG = "[Spotlight VM]"
    }

    init {
        loadInitialSettings()
        performHealthCheckAndRefresh()
        viewModelScope.launch {
            repository.xposedActive.collect { active ->
                _uiState.update { it.copy(xposedActive = active) }
            }
        }
    }

    fun onModuleToggled() {
        _uiState.update { currentState ->
            val newState = !currentState.moduleEnabled
            repository.moduleEnabled = newState
            currentState.copy(moduleEnabled = newState)
        }
    }

    fun selectedMediaSourceFrom(value: Int) {
        val source = MediaSource.fromValue(value)
        _uiState.update { currentState ->
            repository.mediaSource = value
            currentState.copy(selectedMediaSource = source)
        }
    }

    fun setCurrentMediaType(type: MediaType) {
        _uiState.update { currentState ->
            repository.localMediaType = type.value
            currentState.copy(currentType = type)
        }
    }

    fun onMediaSelected(type: MediaType, uri: Uri) {
        getMediaUri(type)?.let {
            try {
                app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* 忽略错误 */ }
        }

        updateState { it.copy(
            processingMedia = it.processingMedia.add(type),
            thumbnails = it.thumbnails.put(type, null)
        ) }

        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Dog.e(TAG, "Failed to take persistable permission", e, repository.enableLog)
        }

        viewModelScope.launch {
            val mimeType = app.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            val fileName = if (extension != null) "${type.label}.${extension}" else type.label
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { inputStream ->
                        repository.prepareRemoteMedia(fileName, inputStream)
                    } ?: false
                }.getOrDefault(false)
            }

            if (success) {
                saveRemoteFileName(type, fileName)
                loadAndVerifyMedia(type, uri)
            } else {
                clearMediaBy(type)
            }

            updateState { it.copy(processingMedia = it.processingMedia.remove(type)) }
        }
    }

    fun clearMediaBy(type: MediaType) {
        when (type) {
            MediaType.VIDEO -> {
                repository.remoteVideoFile?.let { repository.deleteRemoteMedia(it) }
                repository.videoUri = null
                repository.remoteVideoFile = null
            }
            MediaType.IMAGE -> {
                repository.remoteImageFile?.let { repository.deleteRemoteMedia(it) }
                repository.imageUri = null
                repository.remoteImageFile = null
            }
        }
        updateThumbnailState(type, null)
        updateState { it.copy(displayPaths = it.displayPaths.put(type, null)) }
    }

    fun getMediaPath(type: MediaType): String? {
        return _uiState.value.displayPaths[type]
            ?: getMediaUri(type)?.toString()
    }

    fun performHealthCheckAndRefresh() {
        viewModelScope.launch {
            MediaType.entries.forEach { type ->
                loadAndVerifyMedia(type)
            }
        }
    }

    private fun saveMediaUri(type: MediaType, uri: String?) {
        when (type) {
            MediaType.VIDEO -> repository.videoUri = uri
            MediaType.IMAGE -> repository.imageUri = uri
        }
    }

    private fun saveRemoteFileName(type: MediaType, fileName: String) {
        when (type) {
            MediaType.VIDEO -> repository.remoteVideoFile = fileName
            MediaType.IMAGE -> repository.remoteImageFile = fileName
        }
    }

    private fun loadInitialSettings() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled,
                xposedActive = repository.xposedActive.value,
                selectedMediaSource = MediaSource.fromValue(repository.mediaSource),
                currentType = MediaType.fromValue(repository.localMediaType)
            )
        }
    }

    private fun getMediaUri(type: MediaType): Uri? {
        return when (type) {
            MediaType.VIDEO -> repository.videoUri?.toUri()
            MediaType.IMAGE -> repository.imageUri?.toUri()
        }
    }

    private suspend fun loadAndVerifyMedia(type: MediaType, uriOverride: Uri? = null) {
        withContext(Dispatchers.IO) {
            val targetUri = uriOverride ?: getMediaUri(type)
            if (targetUri == null) {
                updateThumbnailState(type, null)
                return@withContext
            }

            val thumbnail = runCatching {
                app.contentResolver.loadThumbnail(targetUri, Size(512, 512), null)
            }.getOrNull()

            if (thumbnail != null) {
                updateThumbnailState(type, thumbnail)
                saveMediaUri(type, targetUri.toString())
                val displayPath = MediaPathResolver.resolveDisplayPath(app, targetUri)
                updateState { it.copy(displayPaths = it.displayPaths.put(type, displayPath)) }
            } else {
                updateThumbnailState(type, null)
                clearMediaBy(type)
            }
        }
    }

    fun updateThumbnailState(type: MediaType, thumbnail: Bitmap?) {
        updateState { it.copy(
            thumbnails = it.thumbnails.put(type, thumbnail)
        ) }
    }
}
