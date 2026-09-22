package com.nothing.camera2magic.hook

import android.content.SharedPreferences
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.LensKeys
import com.nothing.camera2magic.utils.LensSlot
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog

object SourceManager {

    private const val TAG = "[MediaSource]"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_SHOW_TOAST = "main_show_toast"
    const val KEY_HOOK_ENABLED_PACKAGES = "hook_enabled_packages"

    private lateinit var prefs: SharedPreferences
    private var rotationListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

    /** 当前生效的媒体 = [activeSlot] 那一槽的媒体（本槽没配就是 null → 该镜头透传真实画面）。所有旁路消费者（YUV 覆写、JPEG 替换）只读它。 */
    @Volatile
    var validMedia: ValidMedia? = null
        private set

    /** 最近一次成功识别槽位的相机所属槽；双摄同开时是「最后打开的那路」。 */
    @Volatile
    var activeSlot: LensSlot = LensSlot.BACK
        private set

    // 两槽各一份，refreshPrefs 一次性算好（含旧键回退），切槽只是查表、不再碰 prefs
    @Volatile
    private var slotMedia: Map<LensSlot, ValidMedia?> = emptyMap()

    /** 该应用是否启用模块。**记账/清理类路径用它**（记错东西不比“少记一笔”更糟，但记账被跳过会直接导致泄漏） */
    val appEnabled: Boolean
        get() = appHookEnabled

    /**
     * 替换门控：只有「应用启用」且「当前镜头真的有一份可播媒体」才允许换面。
     *
     * `validMedia` 这一项是故命的：光凭 app_hook_<pkg> 就换面，会得到
     * 「App 的预览面被 BlackHole 换走、而原生引擎又没有帧源可画」= 黑屏/冻结帧；
     * Camera1 的 `onPictureTaken` 还会在无帧源时把照片写成空/坏 JPEG。收在这个单点里，
     * 是为了让全部替换点一起变透传——逐点加 `&&` 必然漏一处。
     * 记账/清理路径用 [appEnabled]，否则换到没配媒体的镜头时连记账都会被跳过。
     */
    val readyForHook: Boolean
        get() = appHookEnabled && validMedia != null

    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
        registerPreferenceListener()
    }

    /**
     * 需要实时生效的全局开关监听。prefs 是 hook 侧的远程 prefs，
     * 宿主侧每次 save 都会同步过来，所以拨开关就能立刻听到变化。
     */
    private fun registerPreferenceListener() {
        if (rotationListener != null) return
        rotationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            runCatching {
                when (key) {
                    // 全局手动旋转变化时实时重新下发，保证运行中立即生效
                    "main_manually_rotate" -> {
                        refreshPrefs()
                        applyManualRotationToNative()
                    }
                    // 播放声音开关：实时改正在播放的 ExoPlayer 音量（图片模式无播放器，空操作）
                    "main_play_sound" -> {
                        refreshPrefs()
                        Camera3().setPlaySound(playSound)
                    }
                }
            }.onFailure { e ->
                Dog.e(TAG, "preference listener ($key) failed: ${e.message}", e, enableLog)
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

    private fun updateState(media: ValidMedia?, suffix: String = "") {
        validMedia = media
        toastMessage = if (media == null) "No media selected." else "${media.type.label} is ready$suffix."
    }

    /**
     * 把该相机所属槽切成当前槽，并按新槽重算 [validMedia]，返回重算后的值。
     * 总是重算而不是「槽位变了才动」：同一槽的媒体可能刚被刷新过，重算才是幂等的。
     * [slot] 为 null（特征读不到）时沿用现槽，经不因判定失败而清空媒体。
     *
     * 必须 @Synchronized：[activeSlot] 与 [validMedia] 是一对必须同步演进的字段，
     * 而双摄并发（两次 openCamera → 两个不同相机线程上的 onConfigured）会并发进来，
     * @Volatile 只保证可见性、不保证跨字段原子，交错会做出
     * 「管线跑 A 槽媒体、YUV/JPEG 旁路读 B 槽」的裂开态。
     */
    @Synchronized
    fun activateSlot(slot: LensSlot?): ValidMedia? {
        if (slot != null) activeSlot = slot
        updateState(slotMedia[activeSlot], " (${activeSlot.id} lens)")
        teardownRenderIfIdle()
        return validMedia
    }

    /**
     * 媒体清空时的主动拆台：原生目标表与渲染管线不会自己停。
     * 不拆的话，「改配置前那一拍」注册过的面会被原生继续画旧帧盖住，
     * 症状就是「已经透传了但看起来还是黑屏/旧画面」。
     * 这是清理动作，绝不判 readyForHook（铁律 1）；幂等，没注册过东西时是空操作。
     * 不动 BlackHole._oab：那批 dummy 面可能还在被未重建的会话写，release 它们会把错误抛回目标应用，
     * 常规 onClosed / Camera1.open 切换路径会收。注意 `Camera3().stop()` 只能停共享 player 与纹理，
     * 图片循环节奏靠 `imageRendering`/`initialized` 下一 tick 自检退出（见 AGENTS 渲染管线一节）。
     */
    private fun teardownRenderIfIdle() {
        if (validMedia != null) return
        runCatching {
            Camera3().stop()
            NB.clearTargets()
            Dog.w(TAG, "media cleared: native render targets cleared and pipeline stopped", enableLog)
        }.onFailure { Dog.e(TAG, "render teardown failed: ${it.message}", it, enableLog) }
    }

    fun refreshAndDispatch() {
        ImageReaderHooker.invalidateCache()
        refreshPrefs()

        updateState(validMedia, validMedia?.let { " (${activeSlot.id} lens)" } ?: "")
        // 用户就是在这里清空媒体的（改配置要回前台才刷）——此刻必须主动拆台，
        // 否则目标应用不重建会话就永远看不到真实画面
        teardownRenderIfIdle()
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            // 原生日志与模块日志开关同步：历史 .so 无视开关常开输出，是关不掉的指纹。
            // 用 runCatching 是因为 UnsatisfiedLinkError 不是 Exception，外层 catch 接不住
            runCatching { NB.setLogEnabled(enableLog) }
            showToast = prefs.getBoolean(KEY_SHOW_TOAST, true)
            manuallyRotate = runCatching { prefs.getInt("main_manually_rotate", 0) }.getOrDefault(0)

            hookEnabledPackages = prefs.getString(KEY_HOOK_ENABLED_PACKAGES, "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            validMedia = null
            slotMedia = emptyMap()
            var legacyMigrated = false
            val pkg = runCatching { GlobalState.processName.substringBefore(":") }.getOrNull()
            if (pkg != null) {
                appHookEnabled = prefs.getBoolean("app_hook_$pkg", true)
                // 旧版不分镜头的配置：只在「未迁移」时作为两槽的回退。
                // 必须用标记而不是「旧键存在与否」判定：旧键可能在远端残留（本地已清、当时 service 未绑定
                // 跳过远程写，而 syncAllToRemote 只补写不删除），那些陈旧 key 会把用户删掉的媒体「复活」。
                // 不包 runCatching：读不到就是整次刷新作废（外层 catch），保留上一轮 slotMedia/validMedia。
                // 就地吐掉会退化成「当作未迁移」，反而把刚刚被阻止的旧媒体复活路径重新打开。
                val migrated = prefs.getBoolean(LensKeys.migrated(pkg), false)
                legacyMigrated = migrated
                val legacy = if (migrated) null else readSlotMedia(
                    modeKey = LensKeys.legacyMediaMode(pkg),
                    photoKey = LensKeys.legacyRemotePhoto(pkg),
                    videoKey = LensKeys.legacyRemoteVideo(pkg),
                    streamKey = null,
                    fallback = null,
                )
                val resolved = LensSlot.entries.associateWith { slot ->
                    readSlotMedia(
                        modeKey = LensKeys.mediaMode(slot, pkg),
                        photoKey = LensKeys.remotePhoto(slot, pkg),
                        videoKey = LensKeys.remoteVideo(slot, pkg),
                        streamKey = LensKeys.streamUrl(slot, pkg),
                        fallback = legacy,
                    )
                }
                // 不做「借用另一槽」：后置配了、前置没配时，前置必须出真实画面而不是后置那份。
                // 单一事实源就是 slotMedia；不能再加「?: legacy」这一层，否则会与
                // activateSlot 的重算结果不一致（刷新时报 ready、下一个会话静默变黑帧）
                slotMedia = resolved
                validMedia = slotMedia[activeSlot]
            }

            // 配置解析的唯一可观测点。例行日志必须受 main_enable_log 门控，禁止改常开——
            // 那会在目标进程 logcat 里留下关不掉的被 hook 指纹
            Dog.i(
                TAG,
                "refreshPrefs: process=$pkg, hookEnabled=$appHookEnabled, readyForHook=$readyForHook, media=${validMedia?.type?.label ?: "none"}, " +
                    "slot=${activeSlot.id}, legacyMigrated=$legacyMigrated, front=${slotMedia[LensSlot.FRONT]?.type?.label ?: "none"}, " +
                    "back=${slotMedia[LensSlot.BACK]?.type?.label ?: "none"}, rotate=$manuallyRotate, packages=$hookEnabledPackages",
                enableLog,
            )
        } catch (e: Exception) { /* Do Nothing */ }
    }

    /**
     * 读一槽的媒体。**模式键是权威的**（与改动前一致）：选了 photo 就只看 photo 文件，
     * 另一模式存了东西也不作数——跨模式兑底会让「配置」下拉框的语义发模糊。
     * 只有整槽没显式配置（三个媒体键都缺**且**模式键也不存在）时才交给 [fallback]（旧版共享配置）。
     * 旧键也没配（或已迁移）时就是 null——**不向另一槽借用**，那一镜头直接输出真实画面。
     *
     * 网络流的「源」是 [streamKey] 里的 URL 字符串，与本地媒体的远程文件名同一层级，
     * 所以它同样参与「整槽是否显式配置」的判定；但它永远不来自旧键（旧版没有按槽的流配置）。
     */
    private fun readSlotMedia(
        modeKey: String,
        photoKey: String,
        videoKey: String,
        streamKey: String?,
        fallback: ValidMedia?,
    ): ValidMedia? {
        val photo = prefs.getString(photoKey, null)
        val video = prefs.getString(videoKey, null)
        val stream = streamKey?.let { prefs.getString(it, null) }
        // 模式键也算「显式配置」：用户把某槽设成 network 却还没填地址时，
        // 三个媒体键都空也不能回退旧键（否则会把用户删掉的旧媒体「复活」）
        if (photo == null && video == null && stream == null && !prefs.contains(modeKey)) return fallback
        return when (prefs.getString(modeKey, "photo") ?: "photo") {
            "photo" -> photo?.let { ValidMedia(it, MagicType.LOCAL_IMAGE) }
            "video" -> video?.let { ValidMedia(it, MagicType.LOCAL_VIDEO) }
            "network" -> stream?.let { ValidMedia(it, MagicType.NETWORK_STREAM) }
            else -> null
        }
    }
}
