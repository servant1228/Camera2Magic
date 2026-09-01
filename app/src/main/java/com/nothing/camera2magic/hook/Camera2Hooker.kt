package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.BlackHole.getBlackHole
import com.nothing.camera2magic.hook.BlackHole.gocBlackHole
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

@SuppressLint("PrivateApi")
class Camera2Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    companion object {
        private const val TAG = "[CAM2]"
        // 仅对打卡 App 做“全 Surface 替换”（录制/处理输出面也换成 BlackHole），
        // 其他作用域内的应用保持旧的只替换预览面（format 1/4）行为，避免误伤。
        private const val FULL_REPLACE_PACKAGE = "com.xinchuzu.driver"
        private const val CAMERA_DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
        private const val CAMERA_MANAGER = "android.hardware.camera2.CameraManager"
        private const val CAPTURE_REQUEST_BUILDER = $$"android.hardware.camera2.CaptureRequest$Builder"
        private var activatedCamera = WeakReference<Any>(null)
        private val camera3Map = WeakHashMap<Any, Camera3>()
        // 与 hookedClasses 一样必须同步：会话创建/addTarget 可能在相机线程写入，
        // 而 onClosed/onConfigured 会在另一线程遍历，裸 WeakHashMap 会抛 CME
        private val extraRenderTargets: MutableSet<Surface> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Surface, Boolean>()))
        private val processName: String
            get() = GlobalState.processName
        private val fullReplaceOutputs: Boolean
            get() = processName.substringBefore(':') == FULL_REPLACE_PACKAGE
        private val CameraDevice.isActiveRef: Boolean
            get() = activatedCamera.get() == this
    }

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))


    init {
        val classLoader = param.classLoader

        classLoader.safeHook(CAMERA_MANAGER) {
            openCameraHook()
        }

        classLoader.safeHook(CAMERA_DEVICE_IMPL) {
            createCaptureSessionWithConfigurationHook()
            createCaptureSessionWithSurfacesHook()
            createCaptureSessionByOutputConfigurationsHook()
        }

        classLoader.safeHook(CAPTURE_REQUEST_BUILDER) {
            addTargetHook()
            removeTargetHook()
        }
    }

    private fun CameraDevice.updateBaseData() {
        val cameraId = this.id
        val context = GlobalState.appContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cm.getCameraCharacteristics(cameraId)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        val sensorOri = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        SM.rememberCameraBaseData(2, facingFront, sensorOri, rotation * 90, processName)
        SM.applyManualRotationToNative()
        activatedCamera = WeakReference(this)
    }

    private fun Class<*>.onOpenedHook() {
        val onOpened = getDeclaredMethod("onOpened", CameraDevice::class.java)
        magic.hook(onOpened).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            // 铁律2：appContext 可能未初始化，getCameraCharacteristics 也会抛
            // CameraAccessException，未兜底就是目标应用闪退
            runCatching {
                val camera = chain.args[0] as CameraDevice
                camera.updateBaseData()
                Dog.w(TAG, "API[2] open camera: ${camera.shortId}", SM.enableLog)
            }.onFailure { Dog.e(TAG, "onOpened failed: ${it.message}", it, true) }
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.onClosedHook() {
        // 部分回调类不重写 onClosed（继承自 CameraDevice.StateCallback），
        // getDeclaredMethod 找不到会抛 NoSuchMethodException，导致关闭时
        // BlackHole/Camera3 清理不执行；这里优先找声明方法，找不到再取继承的公有实现。
        val onClosed = runCatching { getDeclaredMethod("onClosed", CameraDevice::class.java) }
            .getOrElse { getMethod("onClosed", CameraDevice::class.java) }
        magic.hook(onClosed).intercept { chain ->
            // 清理路径不判 readyForHook：会话打开时门控为开、关闭时被用户关掉，
            // 判门控会整段跳过清理，直接泄漏 Surface/纹理
            runCatching {
                val camera = chain.args[0] as CameraDevice
                if (camera.isActiveRef) {
                    camera3Map[camera]?.stop()
                    synchronized(extraRenderTargets) {
                        extraRenderTargets.forEach { runCatching { NB.removeRenderTarget(it) } }
                        extraRenderTargets.clear()
                    }
                    BlackHole.clear()
                    Dog.w(TAG, "API[2] close camera: ${camera.shortId}", SM.enableLog)
                }
            }.onFailure { Dog.e(TAG, "onClosed cleanup failed: ${it.message}", it, true) }

            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.openCameraHook() {
        val openCamera = getDeclaredMethod("openCamera",
            String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java)
        magic.hook(openCamera).intercept { chain ->
            val stateCallBack = chain.args[1] as CameraDevice.StateCallback
            stateCallBack.javaClass.safeHook {
                onOpenedHook()
                onClosedHook()
            }
            return@intercept chain.proceed()
        }

        val openCameraWithExecutor = getDeclaredMethod("openCamera",
            String::class.java, Executor::class.java, CameraDevice.StateCallback::class.java)
        magic.hook(openCameraWithExecutor).intercept { chain ->
            val stateCallBack = chain.args[2] as CameraDevice.StateCallback
            stateCallBack.javaClass.safeHook {
                onOpenedHook()
                onClosedHook()
            }
            return@intercept chain.proceed()
        }
    }

    @OptIn(UnstableApi::class)
    private fun Class<*>.onConfiguredHook() {
        val onConfigured = getDeclaredMethod("onConfigured",
            CameraCaptureSession::class.java)

        magic.hook(onConfigured).intercept { chain ->
            // 铁律1：这里是真正下发渲染目标并启动 Camera3 的地方。
            // 缺门控时，因 safeHook 的去重是永久的，运行中关掉 app_hook_<pkg>
            // 也无法阻止继续替换画面。
            if (!SM.readyForHook) return@intercept chain.proceed()
            runCatching {
                val session = chain.args[0] as CameraCaptureSession
                val camera = session.device
                Dog.i(TAG, "[:onConfigured] ${BlackHole.originSurfaces.size} surface need to send.", SM.enableLog)
                BlackHole.originSurfaces.forEach { NB.addRenderTarget(it) }
                synchronized(extraRenderTargets) {
                    extraRenderTargets.forEach { NB.addRenderTarget(it) }
                }
                SM.validMedia?.let {
                    val camera3 = Camera3()
                    camera3Map[camera] = camera3
                    camera3.start(magic, it)
                }
                // 会话配置完成时重新下发一次 base data，确保手动旋转生效
                SM.applyManualRotationToNative()
            }.onFailure { Dog.e(TAG, "onConfigured failed: ${it.message}", it, true) }
            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.onConfigureFailedHook() {
        val onConfigureFailed = getDeclaredMethod("onConfigureFailed",
            CameraCaptureSession::class.java)

        magic.hook(onConfigureFailed).intercept { chain ->
            Dog.e(TAG, "CameraCaptureSession.StateCallback: onConfigureFailed.", null, true)
            chain.proceed()
        }
    }
    private fun Class<*>.createCaptureSessionWithConfigurationHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()

            // 铁律2：mSurfaces 是 @SoonBlockedPrivateApi 私有字段，
            // 未来版本改名即抛 NoSuchFieldException；失败只放行原调用
            runCatching {
                val sessionConfiguration = chain.args[0] as SessionConfiguration

                sessionConfiguration.stateCallback.javaClass.safeHook {
                    onConfiguredHook()
                    onConfigureFailedHook()
                }

                @SuppressLint("SoonBlockedPrivateApi")
                val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
                field.isAccessible = true
                sessionConfiguration.outputConfigurations.forEach { outputConfiguration ->
                    val surfaces = outputConfiguration.surfaces
                    Dog.i(TAG, "[:createCaptureSession] outputConfiguration: $surfaces", SM.enableLog)

                    val modifiedSurfaces = surfaces.map { origin ->
                        val (w, h, f) = NB.getSurfaceInfo(origin)
                        Dog.i(TAG, "    surface ${origin.shortId} format=$f size=${w}x${h}", SM.enableLog)
                        if (f == 35) NB.updateAlgorithmSize(w, h)
                        if (fullReplaceOutputs) {
                            extraRenderTargets.add(origin)
                            return@map origin.gocBlackHole
                        }
                        if (f == 1 || f == 4) return@map origin.gocBlackHole
                        extraRenderTargets.add(origin)
                        return@map origin
                    }
                    field.set(outputConfiguration, modifiedSurfaces)
                }
            }.onFailure { Dog.e(TAG, "createCaptureSession(SessionConfiguration) failed: ${it.message}", it, true) }
            chain.proceed()
        }
    }

    private fun Class<*>.createCaptureSessionWithSurfacesHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()

            // 这个变体不碰私有字段，改为整体换参；失败则放行原参数
            val newArgs = runCatching {
                val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
                stateCallback.javaClass.safeHook {
                    onConfiguredHook()
                    onConfigureFailedHook()
                }

                @Suppress("UNCHECKED_CAST")
                val surfaces = chain.args[0] as List<Surface>
                Dog.i(TAG, "[:createCaptureSession] List<Surface>: ${surfaces.size}", SM.enableLog)
                val newList = surfaces.mapTo(ArrayList()) { origin ->
                    val (w, h, f) = NB.getSurfaceInfo(origin)
                    Dog.i(TAG, "    surface ${origin.shortId} format=$f size=${w}x${h}", SM.enableLog)
                    if (f == 35) NB.updateAlgorithmSize(w, h)
                    if (fullReplaceOutputs) {
                        extraRenderTargets.add(origin)
                        return@mapTo origin.gocBlackHole
                    }
                    if (f == 1 || f == 4) return@mapTo origin.gocBlackHole
                    extraRenderTargets.add(origin)
                    return@mapTo origin
                }

                chain.args.toTypedArray().also { it[0] = newList }
            }.onFailure {
                Dog.e(TAG, "createCaptureSession(List<Surface>) failed: ${it.message}", it, true)
            }.getOrNull() ?: return@intercept chain.proceed()

            chain.proceed(newArgs)
        }
    }

    private fun Class<*>.createCaptureSessionByOutputConfigurationsHook() {
        val createCaptureSession = getDeclaredMethod(
            "createCaptureSessionByOutputConfigurations",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)
        magic.hook(createCaptureSession).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()

            // 同样依赖 mSurfaces 私有字段，失败只放行
            runCatching {
                val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
                stateCallback.javaClass.safeHook {
                    onConfiguredHook()
                    onConfigureFailedHook()
                }

                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val configs = chain.args[0] as List<OutputConfiguration>
                Dog.i(TAG, "[:createCaptureSessionByOutputConfigs] ${configs.size} configs", SM.enableLog)

                @SuppressLint("SoonBlockedPrivateApi")
                val field = OutputConfiguration::class.java.getDeclaredField("mSurfaces")
                field.isAccessible = true
                configs.forEach { config ->
                    val surfaces = config.surfaces
                    val modifiedSurfaces = surfaces.map { origin ->
                        val (w, h, f) = NB.getSurfaceInfo(origin)
                        Dog.i(TAG, "    surface ${origin.shortId} format=$f size=${w}x${h}", SM.enableLog)
                        if (f == 35) NB.updateAlgorithmSize(w, h)
                        if (fullReplaceOutputs) {
                            extraRenderTargets.add(origin)
                            return@map origin.gocBlackHole
                        }
                        if (f == 1 || f == 4) return@map origin.gocBlackHole
                        extraRenderTargets.add(origin)
                        return@map origin
                    }
                    field.set(config, modifiedSurfaces)
                }
            }.onFailure {
                Dog.e(TAG, "createCaptureSessionByOutputConfigurations failed: ${it.message}", it, true)
            }
            chain.proceed()
        }
    }

    private fun Class<*>.addTargetHook() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            // getSurfaceInfo 是 native 契约，返回不足 3 元素时解构会抛 AIOOBE
            val replacement = runCatching {
                val origin = chain.args[0] as Surface
                val (width, height, format) = NB.getSurfaceInfo(origin)
                Dog.i(TAG, "[:addTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)

                if (format == 35) NB.updateAlgorithmSize(width, height)
                if (fullReplaceOutputs) {
                    extraRenderTargets.add(origin)
                    return@runCatching origin.gocBlackHole
                }
                if (format == 1 || format == 4) return@runCatching origin.gocBlackHole
                // 非预览面不换：它在会话创建阶段已被登记进 extraRenderTargets，
                // 这里再登记一次没有意义（removeTarget 靠 getBlackHole 映射兜住两种模式）
                null
            }.onFailure { Dog.e(TAG, "addTarget failed: ${it.message}", it, true) }.getOrNull()

            if (replacement != null) return@intercept chain.proceed(arrayOf(replacement))
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.removeTargetHook() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val oab = runCatching {
                val origin = chain.args[0] as Surface
                val (width, height, format) = NB.getSurfaceInfo(origin)
                Dog.i(TAG, "[:removeTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)
                // 必须把 BlackHole 映射回原 Surface 再传给原实现，
                // 传替换面会让原生引擎的目标表错乱
                origin.getBlackHole ?: origin
            }.onFailure { Dog.e(TAG, "removeTarget failed: ${it.message}", it, true) }.getOrNull()

            if (oab != null) return@intercept chain.proceed(arrayOf(oab))
            return@intercept chain.proceed()
        }
    }
}



