package com.nothing.camera2magic.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.camera2magic.BuildConfig
import com.nothing.camera2magic.utils.Dog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val moduleEnabled: Boolean = true,
    val xposedActive: Boolean = false,
    val hookMode: String = "Camera2",
    val currentMediaType: MediaType = MediaType.VIDEO,
    val versionName: String = BuildConfig.VERSION_NAME,
    val runningTimeSeconds: Long = 0L,
    val playSound: Boolean = false,
    val enableLog: Boolean = false,
    val injectMenu: Boolean = false,
    val manuallyRotate: Int = 0,
    val scopeAppList: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
)

class HomeViewModel(
    private val app: Application,
    private val repository: ConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    private var runningStartTime: Long = 0L
    private var isRunning = false

    companion object {
        private const val TAG = "[Home VM]"
    }

    init {
        loadInitialState()
        startRunningTimer()
        Dog.enabled = repository.enableLog
        Dog.i(TAG, "Camera2Magic started, module=${repository.moduleEnabled}", repository.enableLog)
        viewModelScope.launch {
            repository.xposedActive.collect { active ->
                _uiState.update { it.copy(xposedActive = active) }
            }
        }
        // Delay read of scope list — Xposed service binds asynchronously
        viewModelScope.launch {
            delay(500)
            val scope = repository.getScopeAppList()
            if (scope != null) {
                _uiState.update { it.copy(scopeAppList = scope) }
            }
        }
    }

    private fun loadInitialState() {
        _uiState.update {
            it.copy(
                moduleEnabled = repository.moduleEnabled,
                xposedActive = repository.xposedActive.value,
                currentMediaType = MediaType.fromValue(repository.localMediaType),
                playSound = repository.playSound,
                enableLog = repository.enableLog,
                injectMenu = repository.injectMenu,
                manuallyRotate = repository.manuallyRotate,
                scopeAppList = repository.getScopeAppList() ?: emptyList(),
            )
        }
    }

    private fun startRunningTimer() {
        runningStartTime = System.currentTimeMillis()
        isRunning = true
        viewModelScope.launch {
            while (isRunning) {
                _uiState.update {
                    it.copy(runningTimeSeconds = (System.currentTimeMillis() - runningStartTime) / 1000L)
                }
                delay(1000L)
            }
        }
    }

    fun onModuleToggle() {
        val newState = !repository.moduleEnabled
        repository.moduleEnabled = newState
        _uiState.update { it.copy(moduleEnabled = newState) }
    }

    fun onModuleActivate() {
        repository.moduleEnabled = true
        _uiState.update { it.copy(moduleEnabled = true) }
        Dog.i(TAG, "module activated", repository.enableLog)
    }

    fun onModuleDeactivate() {
        repository.moduleEnabled = false
        _uiState.update { it.copy(moduleEnabled = false) }
        Dog.w(TAG, "module deactivated", repository.enableLog)
    }

    fun onMediaTypeChanged(type: MediaType) {
        repository.localMediaType = type.value
        _uiState.update { it.copy(currentMediaType = type) }
    }

    fun onPlaySoundToggled() {
        val newState = !repository.playSound
        repository.playSound = newState
        _uiState.update { it.copy(playSound = newState) }
    }

    fun onEnableLogToggled() {
        val newState = !repository.enableLog
        repository.enableLog = newState
        Dog.enabled = newState
        _uiState.update { it.copy(enableLog = newState) }
        Dog.i(TAG, "logging ${if (newState) "enabled" else "disabled"}", true)
    }

    fun onScopeChanged(packages: List<String>) {
        repository.hookEnabledPackages = packages
    }

    fun refreshScopeList() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val scope = repository.getScopeAppList()
            _uiState.update {
                it.copy(
                    scopeAppList = scope ?: it.scopeAppList,
                    isRefreshing = false,
                )
            }
        }
    }

    fun onInjectMenuToggled() {
        val newState = !repository.injectMenu
        repository.injectMenu = newState
        _uiState.update { it.copy(injectMenu = newState) }
    }

    fun onHookModeChanged(mode: String) {
        repository.hookMode = mode
        _uiState.update { it.copy(hookMode = mode) }
    }

    override fun onCleared() {
        isRunning = false
        super.onCleared()
    }
}
