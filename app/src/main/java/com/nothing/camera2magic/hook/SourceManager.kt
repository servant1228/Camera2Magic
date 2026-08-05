package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.core.net.toUri
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import java.io.FileNotFoundException

object SourceManager {

    private const val TAG = "[MediaSource]"
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_SHOW_TOAST = "main_show_toast"
    private const val KEY_ADAPT_LANDSCAPE = "main_adapt_landscape"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_VIDEO_FILE = "remote_video_file"
    private const val KEY_IMAGE_FILE = "remote_image_file"
    private const val KEY_NETWORK_RTSP_URI = "network_rtsp_uri"
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
    var compressJpeg: Boolean = true
        private set
    @Volatile
    var fixPhotoRotation: Boolean = false
        private set
    @Volatile
    var adaptLandscape: Boolean = false
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
    private var mediaSource: Int = 0
    @Volatile
    private var mediaType: Int = 0
    @Volatile
    private var selectedMedia: Int = 0x0000
    @Volatile
    var toastMessage: String? = null
    @Volatile
    private var videoFile: String? = null
    @Volatile
    private var imageFile: String? = null
    @Volatile
    private var rtspUri: String? = null

    val readyForHook: Boolean
        get() {
            if (!moduleEnabled) return false
            return appHookEnabled
        }
    @Volatile
    var validMedia: ValidMedia? = null
        private set

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
                // 照片方向修正变化时刷新配置，下一次拍照即生效
                if (key == "main_fix_photo_rotation") {
                    refreshPrefs()
                }
                // 横屏适配开关变化时实时重新下发，保证运行中立即生效
                if (key == KEY_ADAPT_LANDSCAPE) {
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
     * 预编译 libcamera3.so 中 updateManualRotation 存储的字段没有任何读取点（死字段），
     * 原生实际消费的旋转输入是 sensorOri（Camera2 预览角度 + YUV 旋转）与
     * displayOri（仅 Camera1 的宽高交换），因此手动旋转通过改写这两个值生效。
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
    fun applyManualRotationToNative(forceNatural: Boolean = false) {
        if (!baseDataSet) return
        val angles = intArrayOf(0, 90, 180, 270)
        val manual = angles[manuallyRotate.coerceIn(0, 3)]
        // 横屏适配开启时以 0 为基准：原生引擎不再按传感器方向旋转媒体帧，
        // 横屏视频/图片按自身方向显示且不会因旋转导致宽高比被压扁（手动旋转仍可叠加）；
        // 拍照生成 JPEG 时传 forceNatural=true 恢复自然方向，避免照片被压扁
        val adapt = if (forceNatural) false else adaptLandscape
        val sensorBase = if (adapt) 0 else baseSensorOri
        val displayBase = if (adapt) 0 else baseDisplayOri
        val sensor = if (manual > 0) (sensorBase + manual) % 360 else sensorBase
        val display = if (manual > 0) manual else displayBase
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

        val type = MagicType.fromValue(selectedMedia)
        val label = type.label

        val source = when (type) {
            MagicType.LOCAL_VIDEO -> videoFile
            MagicType.LOCAL_IMAGE -> imageFile
            MagicType.NETWORK_RTSP -> rtspUri
        }

        if (source == null) {
            updateState(null, "$label does not exist")
            return
        }

        updateState(ValidMedia(source, type), "$label is ready.")
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            showToast = prefs.getBoolean(KEY_SHOW_TOAST, true)
            compressJpeg = prefs.getBoolean("main_compress_jpeg", true)
            fixPhotoRotation = prefs.getBoolean("main_fix_photo_rotation", false)
            adaptLandscape = prefs.getBoolean(KEY_ADAPT_LANDSCAPE, false)
            manuallyRotate = runCatching { prefs.getInt("main_manually_rotate", 0) }.getOrDefault(0)
            Dog.w(TAG, "refreshPrefs: manuallyRotate=$manuallyRotate, adaptLandscape=$adaptLandscape, compressJpeg=$compressJpeg", true)

            mediaSource = prefs.getInt(KEY_MEDIA_SOURCE, 0)
            mediaType = prefs.getInt(KEY_LOCAL_MEDIA_TYPE, 0)

            selectedMedia = (mediaSource shl 8) or mediaType

            videoFile = prefs.getString(KEY_VIDEO_FILE, null)
            imageFile = prefs.getString(KEY_IMAGE_FILE, null)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, null)
            hookEnabledPackages = prefs.getString(KEY_HOOK_ENABLED_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            val pkg = runCatching { GlobalState.processName.substringBefore(":") }.getOrNull()
            if (pkg != null) {
                appHookEnabled = prefs.getBoolean("app_hook_$pkg", true)
                val appMode = prefs.getString("app_media_mode_$pkg", "global") ?: "global"
                when (appMode) {
                    "photo" -> {
                        val photo = prefs.getString("app_remote_photo_$pkg", null)
                        if (photo != null) {
                            imageFile = photo
                            selectedMedia = (0 shl 8) or 1
                        }
                    }
                    "video" -> {
                        val video = prefs.getString("app_remote_video_$pkg", null)
                        if (video != null) {
                            videoFile = video
                            selectedMedia = (0 shl 8) or 0
                        }
                    }
                }
            }

            Dog.i(TAG, "refreshPrefs: hookEnabledPackages=$hookEnabledPackages")

        } catch (e: Exception) { /* Do Nothing */ }
    }
}
