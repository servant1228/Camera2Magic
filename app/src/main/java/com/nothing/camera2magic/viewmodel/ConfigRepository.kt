package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.LensKeys
import com.nothing.camera2magic.utils.LensSlot
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

        // 框架版本（如 "1.10.1 (10798)"），服务绑定时抓取；binder 死亡不清空（框架版本是设备级事实）
        private val _frameworkInfo = MutableStateFlow<String?>(null)

        // 进程内唯一的 service listener：只触碰 companion 状态，
        // 不持有任何 ConfigRepository 实例（Activity 重建会 new 新实例，实例级 listener 会泄漏并重复 syncAllToRemote）
        private val serviceListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                sharedService = service
                _xposedActive.value = true
                Dog.i(TAG, "xposed service bound")
                _frameworkInfo.value = runCatching {
                    "${service.frameworkVersion} (${service.frameworkVersionCode})"
                }.onFailure { e ->
                    Dog.e(TAG, "[:IPC Error] framework info: ${e.message}", e)
                }.getOrNull()
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

    val frameworkInfo: StateFlow<String?> = _frameworkInfo.asStateFlow()

    init {
        activePrefs = prefs
        // Dog.enabled 的唯一赋值点（宿主进程）：init 从 prefs 恢复 + enableLog setter 实时更新，
        // 使 companion serviceListener 的日志不再依赖 VM 创建时序
        Dog.enabled = enableLog
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

    /**
     * 批量双写：一次本地 edit + 一次远程 edit。[save] 是每键一次 binder 事务，
     * 「要么全成、要么没发生」这种成组写入（例如旧配置迁移）摊成十几个事务会在组合期掉帧。
     */
    private fun saveBatch(entries: List<Pair<String, Any?>>) {
        prefs.edit { entries.forEach { (key, value) -> putAny(key, value) } }
        safeExecute { service ->
            service.getRemotePreferences(GROUP_NAME).edit {
                entries.forEach { (key, value) -> putAny(key, value) }
            }
            Dog.i(TAG, "remote saved ${entries.size} keys in one batch")
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
        set(value) {
            save("main_enable_log", value)
            Dog.enabled = value
        }

    var showToast: Boolean
        get() = prefs.getBoolean("main_show_toast", true)
        set(value) = save("main_show_toast", value)

    var injectMenu: Boolean
        get() = prefs.getBoolean("main_inject_menu", false)
        set(value) = save("main_inject_menu", value)

    var manuallyRotate: Int
        get() = runCatching { prefs.getInt("main_manually_rotate", 0) }.getOrDefault(0)
        set(value) = save("main_manually_rotate", value)

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
        get() = prefs.getString("theme_floating_bottom_bar", "true") == "true"
        set(value) = save("theme_floating_bottom_bar", value.toString())

    var themeFloatingBottomBarStyle: String
        get() = prefs.getString("theme_floating_bottom_bar_style", "ios_like") ?: "ios_like"
        set(value) = save("theme_floating_bottom_bar_style", value)

    var themeBottomBarMode: String
        get() = prefs.getString("theme_bottom_bar_mode", "icon_and_text") ?: "icon_and_text"
        set(value) = save("theme_bottom_bar_mode", value)

    var themeDensityScale: Float
        get() = prefs.getString("theme_density_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        set(value) = save("theme_density_scale", value.toString())

    var themePredictiveBack: Boolean
        get() = prefs.getBoolean("theme_predictive_back", true)
        set(value) = save("theme_predictive_back", value)

    // 作用域列表/应用配置页使用的图标包（空串 = 用应用自带图标）
    var themeIconPack: String
        get() = prefs.getString("theme_icon_pack", "") ?: ""
        set(value) = save("theme_icon_pack", value)

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

    // 旧版不分镜头的媒体键：只读——仅供 migrateLensMediaIfNeeded 取值，不再有写入口。
    // 不保留 setter 是故意的：迁移完成后槽位键会遮蔽旧键，往旧键写会变成静默不生效的配置。

    fun getAppMediaMode(packageName: String): String =
        prefs.getString(LensKeys.legacyMediaMode(packageName), "photo") ?: "photo"

    fun getAppPhotoUri(packageName: String): String? =
        prefs.getString(LensKeys.legacyPhotoUri(packageName), null)

    fun getAppVideoUri(packageName: String): String? =
        prefs.getString(LensKeys.legacyVideoUri(packageName), null)

    fun getAppRemotePhoto(packageName: String): String? =
        prefs.getString(LensKeys.legacyRemotePhoto(packageName), null)

    fun getAppRemoteVideo(packageName: String): String? =
        prefs.getString(LensKeys.legacyRemoteVideo(packageName), null)

    // Per-app config: 镜头槽位（前置/后置各一份媒体，键名单点在 LensKeys）

    fun getAppMediaMode(slot: LensSlot, packageName: String): String =
        prefs.getString(LensKeys.mediaMode(slot, packageName), "photo") ?: "photo"

    fun setAppMediaMode(slot: LensSlot, packageName: String, mode: String) =
        save(LensKeys.mediaMode(slot, packageName), mode)

    fun getAppPhotoUri(slot: LensSlot, packageName: String): String? =
        prefs.getString(LensKeys.photoUri(slot, packageName), null)

    fun setAppPhotoUri(slot: LensSlot, packageName: String, uri: String?) =
        save(LensKeys.photoUri(slot, packageName), uri)

    fun getAppVideoUri(slot: LensSlot, packageName: String): String? =
        prefs.getString(LensKeys.videoUri(slot, packageName), null)

    fun setAppVideoUri(slot: LensSlot, packageName: String, uri: String?) =
        save(LensKeys.videoUri(slot, packageName), uri)

    fun getAppRemotePhoto(slot: LensSlot, packageName: String): String? =
        prefs.getString(LensKeys.remotePhoto(slot, packageName), null)

    fun setAppRemotePhoto(slot: LensSlot, packageName: String, fileName: String?) =
        save(LensKeys.remotePhoto(slot, packageName), fileName)

    fun getAppRemoteVideo(slot: LensSlot, packageName: String): String? =
        prefs.getString(LensKeys.remoteVideo(slot, packageName), null)

    fun setAppRemoteVideo(slot: LensSlot, packageName: String, fileName: String?) =
        save(LensKeys.remoteVideo(slot, packageName), fileName)

    // 网络视频流：URL 直接双写本地 + 远程 prefs，不需要文件拷贝（它本来就是字符串）
    fun getAppStreamUrl(slot: LensSlot, packageName: String): String? =
        prefs.getString(LensKeys.streamUrl(slot, packageName), null)

    fun setAppStreamUrl(slot: LensSlot, packageName: String, url: String?) =
        save(LensKeys.streamUrl(slot, packageName), url)

    /** 该远程文件名是否还被任一槽位引用（迁移后两槽可能指向同一个文件，删除前必须判）。 */
    fun isRemoteMediaReferenced(fileName: String, packageName: String): Boolean =
        LensSlot.entries.any {
            getAppRemotePhoto(it, packageName) == fileName || getAppRemoteVideo(it, packageName) == fileName
        }

    /**
     * 一次性把旧的「不分镜头」媒体配置展开成前置/后置两份，之后应用配置页只读写槽位键。
     *
     * 清旧键是必需的而不是美化：留着它，Hook 侧的旧键回退链会在用户删掉某槽媒体后把旧画面「复活」。
     * 从没进过应用配置页的应用不迁移，仍由回退链按旧语义跑（两槽共用）。
     */
    fun migrateLensMediaIfNeeded(packageName: String) {
        if (prefs.getBoolean(LensKeys.migrated(packageName), false)) return
        val mode = getAppMediaMode(packageName)
        val photo = getAppRemotePhoto(packageName)
        val video = getAppRemoteVideo(packageName)
        val photoUri = getAppPhotoUri(packageName)
        val videoUri = getAppVideoUri(packageName)
        if (mode == "photo" && photo == null && video == null && photoUri == null && videoUri == null) {
            // 无旧配置可搬：只落迁移标记，避免每次进页面都重跑一遍写入
            save(LensKeys.migrated(packageName), true)
            return
        }
        // 成组写入走批量：一次本地 edit + 一次远程 edit，而不是 11 个 binder 事务（这个函数在页面组装期跑）
        val entries = buildList<Pair<String, Any?>> {
            LensSlot.entries.forEach { slot ->
                add(LensKeys.mediaMode(slot, packageName) to mode)
                add(LensKeys.remotePhoto(slot, packageName) to photo)
                add(LensKeys.remoteVideo(slot, packageName) to video)
                add(LensKeys.photoUri(slot, packageName) to photoUri)
                add(LensKeys.videoUri(slot, packageName) to videoUri)
            }
            // 清旧键是语义必需：留着它们，Hook 侧的回退链会把用户删掉的媒体「复活」
            add(LensKeys.legacyMediaMode(packageName) to null)
            add(LensKeys.legacyRemotePhoto(packageName) to null)
            add(LensKeys.legacyRemoteVideo(packageName) to null)
            add(LensKeys.legacyPhotoUri(packageName) to null)
            add(LensKeys.legacyVideoUri(packageName) to null)
            add(LensKeys.migrated(packageName) to true)
        }
        saveBatch(entries)
        Dog.i(TAG, "lens media config migrated: $packageName (mode=$mode photo=$photo video=$video)")
    }
}
