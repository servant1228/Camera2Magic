package com.nothing.camera2magic.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.ui.theme.readThemeConfig
import com.nothing.camera2magic.ui.theme.writeThemeConfig
import com.nothing.camera2magic.utils.Dog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class SettingsUiState(
    val playSound: Boolean = false,
    val enableLog: Boolean = false,
    val showToast: Boolean = true,
    val injectMenu: Boolean = false,
    val manuallyRotate: Int = 0,
    val compressJpeg: Boolean = true,
    val themeConfig: ThemeConfig = ThemeConfig(),
)

class SettingsViewModel(
    private val app: Application,
    private val repository: ConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.value = SettingsUiState(
            playSound = repository.playSound,
            enableLog = repository.enableLog,
            showToast = repository.showToast,
            injectMenu = repository.injectMenu,
            manuallyRotate = repository.manuallyRotate,
            compressJpeg = repository.compressJpeg,
            themeConfig = readThemeConfig(repository),
        )
    }

    fun onThemeConfigChanged(config: ThemeConfig) {
        writeThemeConfig(repository, config)
        _uiState.update { it.copy(themeConfig = config) }
    }

    fun onPlaySoundChanged(value: Boolean) {
        repository.playSound = value
        _uiState.update { it.copy(playSound = value) }
    }

    fun onEnableLogChanged(value: Boolean) {
        repository.enableLog = value
        Dog.enabled = value
        _uiState.update { it.copy(enableLog = value) }
    }

    fun onInjectMenuChanged(value: Boolean) {
        repository.injectMenu = value
        _uiState.update { it.copy(injectMenu = value) }
    }

    fun onShowToastChanged(value: Boolean) {
        repository.showToast = value
        _uiState.update { it.copy(showToast = value) }
    }

    fun onManuallyRotateChanged(value: Int) {
        repository.manuallyRotate = value
        _uiState.update { it.copy(manuallyRotate = value) }
    }

    fun onCompressJpegChanged(value: Boolean) {
        repository.compressJpeg = value
        _uiState.update { it.copy(compressJpeg = value) }
    }
}
