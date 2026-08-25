package com.nothing.camera2magic.hook

import android.content.SharedPreferences
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog

object SourceManager {

    private const val TAG = "[MediaSource]"
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_SHOW_TOAST = "main_show_toast"
    const val KEY_HOOK_ENABLED_PACKAGES = "hook_enabled_packages"

    private lateinit var prefs: SharedPreferences
    private var rotationListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    var moduleEnabled: Boolean = true
        private set
    @Volatile
    var playSound: Boolean = false
        private set
    @Volatile
    var enableLog: Boolean = false
        private set
    @Volatile
    var showToast: Boolean = true
        private set
    @Volatile
    var manuallyRotate: Int = 0
        private set
    // 最近一次 Hook 侧下发的相机 base data，用于实时叠加手动旋转后重新下发
    @Volatile
    private var baseApi: Int = 0
    @Volatile
    private var baseFacingFront: Boolean = false
    @Volatile
    private var baseSensorOri: Int = 0
    @Volatile
    private var baseDisplayOri: Int = 0
    @Volatile
    private var baseProcessName: String = ""
    @Volatile
    private var baseDataSet: Boolean = false
    @Volatile
    var hookEnabledPackages: Set<String> = emptySet()
        private set
    @Volatile
    var appHookEnabled: Boolean = true
        private set
    @Volatile
    var toastMessage: String? = null
    @Volatile
    var validMedia: ValidMedia? = null
        private set

    val readyForHook: Boolean
        get() {
            if (!moduleEnabled) return false
            return appHookEnabled
        }

    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
        registerRotationListener()
    }

    private fun registerRotationListener() {
        if (rotationListener != null) return
        rotationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            runCatching {
                // 全局手动旋转变化时实时重新下发，保证运行中立即生效
                if (key == "main_manually_rotate") {
                    refreshPrefs()
                    applyManualRotationToNative()
                }
            }.onFailure { e ->
                Dog.e(TAG, "apply manual rotation failed: ${e.message}", e, enableLog)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(rotationListener)
    }

    /**
     * 记录 Hook 侧最近一次相机 base data。
     * 手动旋转通过改写 updateCameraBaseData 的 sensorOri（预览角度 + YUV 旋转）与
     * displayOri（仅 Camera1 的宽高交换）实时生效。
     */
    fun rememberCameraBaseData(
        api: Int,
        facingFront: Boolean,
        sensorOri: Int,
        displayOri: Int,
        processName: String,
    ) {
        baseApi = api
        baseFacingFront = facingFront
        baseSensorOri = sensorOri
        baseDisplayOri = displayOri
        baseProcessName = processName
        baseDataSet = true
    }

    /** 把当前手动旋转角度叠加进 base data 并重新下发原生引擎；手动旋转为 0 时恢复原值。 */
    fun applyManualRotationToNative() {
        if (!baseDataSet) return
        val angles = intArrayOf(0, 90, 180, 270)
        val manual = angles[manuallyRotate.coerceIn(0, 3)]
        val sensor = if (manual > 0) (baseSensorOri + manual) % 360 else baseSensorOri
        val display = if (manual > 0) manual else baseDisplayOri
        NB.updateCameraBaseData(baseApi, baseFacingFront, sensor, display, baseProcessName)
    }

    private fun updateState(media: ValidMedia?, message: String) {
        validMedia = media
        toastMessage = message
    }

    fun refreshAndDispatch() {
        ImageReaderHooker.invalidateCache()
        refreshPrefs()
        if (!moduleEnabled) {
            updateState(null, "Module disabled.")
            return
        }

        val media = validMedia
        if (media == null) {
            updateState(null, "No media selected.")
            return
        }

        updateState(media, "${media.type.label} is ready.")
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            showToast = prefs.getBoolean(KEY_SHOW_TOAST, true)
            manuallyRotate = runCatching { prefs.getInt("main_manually_rotate", 0) }.getOrDefault(0)
            Dog.w(TAG, "refreshPrefs: manuallyRotate=$manuallyRotate", true)

            hookEnabledPackages = prefs.getString(KEY_HOOK_ENABLED_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            validMedia = null
            val pkg = runCatching { GlobalState.processName.substringBefore(":") }.getOrNull()
            if (pkg != null) {
                appHookEnabled = prefs.getBoolean("app_hook_$pkg", true)
                val appMode = prefs.getString("app_media_mode_$pkg", "photo") ?: "photo"
                validMedia = when (appMode) {
                    "photo" -> prefs.getString("app_remote_photo_$pkg", null)
                        ?.let { ValidMedia(it, MagicType.LOCAL_IMAGE) }
                    "video" -> prefs.getString("app_remote_video_$pkg", null)
                        ?.let { ValidMedia(it, MagicType.LOCAL_VIDEO) }
                    else -> null
                }
            }

            Dog.i(TAG, "refreshPrefs: hookEnabledPackages=$hookEnabledPackages")

        } catch (e: Exception) { /* Do Nothing */ }
    }
}
