package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


private const val TAG = "[VCX][ConfigRepo]"
private const val GROUP_NAME = "camera_magic_config"

class ConfigRepository(private val prefs: SharedPreferences) {
    companion object {
        @Volatile
        private var sharedService: XposedService? = null

        // 供进程级 listener 全量同步用的 prefs 引用（Android 同名 prefs 本就是单例，重建实例只是刷新指向）
        @Volatile
        private var activePrefs: SharedPreferences? = null

        @Volatile
        private var listenerRegistered = false

        private val _xposedActive = MutableStateFlow(false)

        // 进程内唯一的 service listener：只触碰 companion 状态，
        // 不持有任何 ConfigRepository 实例（Activity 重建会 new 新实例，实例级 listener 会泄漏并重复 syncAllToRemote）
        private val serviceListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                sharedService = service
                _xposedActive.value = true
                Dog.i(TAG, "xposed service bound")
                syncAllToRemote()
            }

            override fun onServiceDied(service: XposedService) {
                sharedService = null
                _xposedActive.value = false
                Dog.w(TAG, "xposed service died")
            }
        }

        private fun syncAllToRemote() {
            val service = sharedService ?: return
            val prefs = activePrefs ?: return
            runCatching {
                service.getRemotePreferences(GROUP_NAME).edit {
                    prefs.all.forEach { (key, value) ->
                        putAny(key, value)
                    }
                }
            }.onFailure { e ->
                Dog.e(TAG, "[:IPC Error] ${e.message}", e)
            }
        }

        private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                else -> remove(key) // 处理 null 或不支持的类型
            }
        }
    }

    val xposedActive: StateFlow<Boolean> = _xposedActive.asStateFlow()

    init {
        activePrefs = prefs
        if (!listenerRegistered) {
            listenerRegistered = true
            XposedServiceHelper.registerListener(serviceListener)
        }
        if (sharedService != null) {
            _xposedActive.value = true
        }
    }

    private fun <R> safeExecute(default: R, block: (XposedService) -> R): R {
        val service = sharedService ?: return default
        return runCatching {
            block(service)
        }.onFailure { e ->
            Dog.e(TAG, "[:IPC Error] ${e.message}", e)
        }.getOrDefault(default)
    }

    private fun safeExecute(block: (XposedService) -> Unit) {
        val service = sharedService ?: return
        runCatching {
            block(service)
        }.onFailure { e ->
            Dog.e(TAG, "[:IPC Error] ${e.message}", e)
        }
    }

    private fun <T> save(key: String, value: T?) {
        prefs.edit { putAny(key, value) }
        safeExecute { service ->
            service.getRemotePreferences(GROUP_NAME).edit {
                putAny(key, value)
            }
            Dog.i(TAG, "remote saved: $key = $value")
        }
    }

    fun getScopeAppList(): List<String>? {
        return safeExecute(null) { it.scope }
    }

    var hookEnabledPackages: List<String>
        get() = prefs.getString("hook_enabled_packages", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = save("hook_enabled_packages", value.joinToString(","))

    fun prepareRemoteMedia(fileName: String, inputStream: InputStream): Boolean {
        return safeExecute(false) { service ->
            service.openRemoteFile(fileName).use {
                FileOutputStream(it.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)
                    inputStream.copyTo(fos, 1024 * 32)
                }
            }
            true
        }
    }

    fun deleteRemoteMedia(fileName: String) {
        safeExecute { it.deleteRemoteFile(fileName) }
    }

    var playSound: Boolean
        get() = prefs.getBoolean("main_play_sound", false)
        set(value) =save("main_play_sound", value)

    var enableLog: Boolean
        get() = prefs.getBoolean("main_enable_log", false)
        set(value) = save("main_enable_log", value)

    var showToast: Boolean
        get() = prefs.getBoolean("main_show_toast", true)
        set(value) = save("main_show_toast", value)

    var injectMenu: Boolean
        get() = prefs.getBoolean("main_inject_menu", false)
        set(value) = save("main_inject_menu", value)

    var manuallyRotate: Int
        get() = runCatching { prefs.getInt("main_manually_rotate", 0) }.getOrDefault(0)
        set(value) = save("main_manually_rotate", value)

    var hookMode: String
        get() = prefs.getString("main_hook_mode", "Camera2") ?: "Camera2"
        set(value) = save("main_hook_mode", value)

    var themeDarkMode: Int
        get() = prefs.getInt("theme_dark_mode", 0)
        set(value) = save("theme_dark_mode", value)

    var themePureBlack: Boolean
        get() = prefs.getString("theme_pure_black", "false") == "true"
        set(value) = save("theme_pure_black", value.toString())

    var themeMonet: Boolean
        get() = prefs.getString("theme_monet", "false") == "true"
        set(value) = save("theme_monet", value.toString())

    var themePaletteStyle: String
        get() = prefs.getString("theme_palette_style", "TonalSpot") ?: "TonalSpot"
        set(value) = save("theme_palette_style", value)

    var themeAccentColor: String
        get() = prefs.getString("theme_accent_color", "Default") ?: "Default"
        set(value) = save("theme_accent_color", value)

    var themeBlurEnabled: Boolean
        get() = prefs.getString("theme_blur", "true") != "false"
        set(value) = save("theme_blur", value.toString())

    var themeFloatingBottomBar: Boolean
        get() = prefs.getString("theme_floating_bottom_bar", "false") == "true"
        set(value) = save("theme_floating_bottom_bar", value.toString())

    var themeFloatingBottomBarStyle: String
        get() = prefs.getString("theme_floating_bottom_bar_style", "miuix") ?: "miuix"
        set(value) = save("theme_floating_bottom_bar_style", value)

    var themeBottomBarMode: String
        get() = prefs.getString("theme_bottom_bar_mode", "icon_and_text") ?: "icon_and_text"
        set(value) = save("theme_bottom_bar_mode", value)

    var themeDensityScale: Float
        get() = prefs.getString("theme_density_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        set(value) = save("theme_density_scale", value.toString())

    var themePredictiveBack: Boolean
        get() = prefs.getBoolean("theme_predictive_back", false)
        set(value) = save("theme_predictive_back", value)

    // Per-app config

    fun getAppHookEnabled(packageName: String): Boolean =
        prefs.getBoolean("app_hook_$packageName", true)

    fun setAppHookEnabled(packageName: String, enabled: Boolean) {
        save("app_hook_$packageName", enabled)
        // Also sync with hook_enabled_packages list so the hook side sees this app
        val packages = hookEnabledPackages.toMutableList()
        if (enabled && packageName !in packages) {
            packages.add(packageName)
            hookEnabledPackages = packages
        } else if (!enabled && packageName in packages) {
            packages.remove(packageName)
            hookEnabledPackages = packages
        }
    }

    fun getAppMediaMode(packageName: String): String =
        prefs.getString("app_media_mode_$packageName", "photo") ?: "photo"

    fun setAppMediaMode(packageName: String, mode: String) =
        save("app_media_mode_$packageName", mode)

    fun getAppPhotoUri(packageName: String): String? =
        prefs.getString("app_photo_uri_$packageName", null)

    fun setAppPhotoUri(packageName: String, uri: String?) =
        save("app_photo_uri_$packageName", uri)

    fun getAppVideoUri(packageName: String): String? =
        prefs.getString("app_video_uri_$packageName", null)

    fun setAppVideoUri(packageName: String, uri: String?) =
        save("app_video_uri_$packageName", uri)

    fun getAppRemotePhoto(packageName: String): String? =
        prefs.getString("app_remote_photo_$packageName", null)

    fun setAppRemotePhoto(packageName: String, fileName: String?) =
        save("app_remote_photo_$packageName", fileName)

    fun getAppRemoteVideo(packageName: String): String? =
        prefs.getString("app_remote_video_$packageName", null)

    fun setAppRemoteVideo(packageName: String, fileName: String?) =
        save("app_remote_video_$packageName", fileName)
}
