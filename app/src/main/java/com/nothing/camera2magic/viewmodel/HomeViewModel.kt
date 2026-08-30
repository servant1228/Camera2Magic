package com.nothing.camera2magic.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nothing.camera2magic.BuildConfig
import com.nothing.camera2magic.utils.Dog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val xposedActive: Boolean = false,
    val hookMode: String = "Camera2",
    val versionName: String = BuildConfig.VERSION_NAME,
    val scopeAppList: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
)

class HomeViewModel(
    private val repository: ConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    companion object {
        private const val TAG = "[Home VM]"
    }

    init {
        loadInitialState()
        Dog.enabled = repository.enableLog
        Dog.i(TAG, "Camera2Magic started", repository.enableLog)
        viewModelScope.launch {
            repository.xposedActive.collect { active ->
                _uiState.update { it.copy(xposedActive = active) }
            }
        }
        // scope 列表依赖 XposedService binder，服务翻真（含进程早已绑好的冷启动）后立即拉取；
        // 服务死亡重绑会再次翻真，列表自动恢复。StateFlow 自带去重，翻假不触发。
        viewModelScope.launch {
            repository.xposedActive
                .filter { it }
                .collect { refreshScopeList() }
        }
    }

    private fun loadInitialState() {
        _uiState.update {
            it.copy(
                xposedActive = repository.xposedActive.value,
                scopeAppList = repository.getScopeAppList() ?: emptyList(),
            )
        }
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

    fun onHookModeChanged(mode: String) {
        repository.hookMode = mode
        _uiState.update { it.copy(hookMode = mode) }
    }

}
