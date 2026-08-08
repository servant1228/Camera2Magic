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
        private val extraRenderTargets = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>()) as MutableSet<Surface>
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
            val camera = chain.args[0] as CameraDevice
            camera.updateBaseData()
            Dog.w(TAG, "API[2] open camera: ${camera.shortId}", SM.enableLog)
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
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.args[0] as CameraDevice
            if (camera.isActiveRef) {
                camera3Map[camera]?.stop()
                extraRenderTargets.forEach { runCatching { NB.removeRenderTarget(it) } }
                extraRenderTargets.clear()
                BlackHole.clear()
                Dog.w(TAG, "API[2] close camera: ${camera.shortId}", SM.enableLog)
            }

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
            val session = chain.args[0] as CameraCaptureSession
            val camera = session.device
            Dog.i(TAG, "[:onConfigured] ${BlackHole.originSurfaces.size} surface need to send.", SM.enableLog)
            BlackHole.originSurfaces.forEach { NB.addRenderTarget(it) }
            extraRenderTargets.forEach { NB.addRenderTarget(it) }
            SM.validMedia?.let {
                val camera3 = Camera3()
                camera3Map[camera] = camera3
                camera3.start(magic, it)
            }
            // 会话配置完成时重新下发一次 base data，确保手动旋转生效
            SM.applyManualRotationToNative()
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

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = newList
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

            val stateCallback = chain.args[1] as CameraCaptureSession.StateCallback
            stateCallback.javaClass.safeHook {
                onConfiguredHook()
                onConfigureFailedHook()
            }

            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val configs = chain.args[0] as List<OutputConfiguration>
            Dog.i(TAG, "[:createCaptureSessionByOutputConfigs] ${configs.size} configs", SM.enableLog)

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
            chain.proceed()
        }
    }

    private fun Class<*>.addTargetHook() {
        val addTarget = getDeclaredMethod("addTarget", Surface::class.java)
        magic.hook(addTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            val (width, height, format) = NB.getSurfaceInfo(origin)
            Dog.i(TAG, "[:addTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)

            if (format == 35) NB.updateAlgorithmSize(width, height)
            if (fullReplaceOutputs) {
                extraRenderTargets.add(origin)
                return@intercept chain.proceed(arrayOf(origin.gocBlackHole))
            }
            if (format == 1 || format == 4) return@intercept chain.proceed(arrayOf(origin.gocBlackHole))
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.removeTargetHook() {
        val removeTarget = getDeclaredMethod("removeTarget", Surface::class.java)
        magic.hook(removeTarget).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val origin = chain.args[0] as Surface
            val (width, height, format) = NB.getSurfaceInfo(origin)
            Dog.i(TAG, "[:removeTarget] ${origin.shortId}, format: $format, size: ${width}x${height}", SM.enableLog)
            val oab = origin.getBlackHole ?: origin
            return@intercept chain.proceed(arrayOf(oab))
        }
    }
}



