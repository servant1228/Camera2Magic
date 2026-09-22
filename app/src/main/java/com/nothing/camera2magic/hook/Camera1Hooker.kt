@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.annotation.SuppressLint

import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture

import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import android.util.Size
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.hook.BlackHole.gocBlackHole
import com.nothing.camera2magic.hook.BlackHole.gocBlackHoleTexture
import com.nothing.camera2magic.utils.LensSlot

@SuppressLint("Recycle")
class Camera1Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager  {
    companion object {
        private const val TAG = "[CAM1]"
        private const val CLS_CAMERA = "android.hardware.Camera"
        private var activatedCamera = WeakReference<Any>(null)

        private val camera3Map: MutableMap<Any, Camera3> =
            Collections.synchronizedMap(WeakHashMap<Any, Camera3>())
        ///////////
        private const val API = 1
        private var facingFront = false
        private var sensorOri = 0
        private var displayOri = 0
        private val processName: String
            get() = GlobalState.processName

        private var vSize = Size(0, 0)
        private var pSize = Size(0, 0)
        /////////////
        private val Camera.isActiveRef: Boolean
            get() = activatedCamera.get() == this
    }

    private val openInterceptor: (Chain) -> Any? = intercept@{ chain ->
        val camera = chain.proceed() as? Camera ?: return@intercept null
        // 记账/清理路径用 appEnabled（同 Camera2 onOpened）：readyForHook 已掺进「有媒体」，
        // 在这里判它会让「换到没配媒体的镜头」跳过清理与 activatedCamera 更新，
        // 上一轮被换走的面就永久泄漏了
        if (!SM.appEnabled) return@intercept camera
        // 新相机打开时若上一台相机仍未 release（App 泄漏相机），先清掉上一轮的渲染状态：
        // 否则原生引擎会继续往上一轮已废弃的 Surface 渲染，第二拍时直接卡死/闪退。
        val oldCamera = activatedCamera.get()
        if (oldCamera != null && oldCamera !== camera) {
            Dog.w(TAG, "camera switched ${oldCamera.shortId} -> ${camera.shortId}, cleaning up previous render state", SM.enableLog)
            runCatching { camera3Map[oldCamera]?.stop() }
            runCatching { NB.clearTargets() }
            BlackHole.clear()
        }
        val cameraId = chain.args.getOrNull(0) as? Int ?: 0
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        activatedCamera = WeakReference(camera)
        facingFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        sensorOri = info.orientation
        // 换面发生在 setPreviewTexture/setPreviewDisplay/startPreview，所以槽位必须在此刻就切到位
        SM.activateSlot(LensSlot.ofFront(facingFront))
        // 外接镜头（facing=2，SDK 37 已无常量）归到后置：非前置即后置是故意的
        Dog.w(TAG, "API[1] open camera: ${camera.shortId}, id=$cameraId, slot=${LensSlot.ofFront(facingFront).id}, facing=${info.facing}, sensor=${info.orientation}, readyForHook=${SM.readyForHook}", SM.enableLog)
        return@intercept camera
    }

    private val previewCallbackInterceptor: (Chain) -> Any? = intercept@ { chain ->
        // 这里只**安装** onPreviewFrame 的钩子，真正覆写在 onPreviewFrame 里另有门控与 validMedia 判定。
        // 用 appEnabled 而不是 readyForHook：否则「没配媒体时设的回调」永远不会被注上，
        // 之后配好媒体也仍不接（Camera1 的 YUV 路径会静默失效）
        if (!SM.appEnabled) return@intercept chain.proceed()
        val originCallback = chain.args[0] as? Camera.PreviewCallback ?: return@intercept chain.proceed()
        originCallback.javaClass.safeHook { onPreviewFrameHook() }
        chain.proceed()
    }

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    init {
        param.classLoader.safeHook(CLS_CAMERA) {
            openHook()
            setParametersHook()
            setPreviewTextureHook()
            setPreviewDisplayHook()
            setDisplayOrientationHook()
            startPreviewHook()
            stopPreviewHook()
            releaseHook()
            setPreviewCallbackHook()
            takePictureHook()
        }
    }

    private fun Camera.Size.toSize(): Size {
        return Size(this.width, this.height)
    }
    private fun Class<*>.openHook() {
        val open = getDeclaredMethod("open")
        val openId = getDeclaredMethod("open", Int::class.java)
        magic.hook(open).intercept(openInterceptor)
        magic.hook(openId).intercept(openInterceptor)
    }
    private fun Class<*>.setParametersHook() {
        val setParameters = getDeclaredMethod("setParameters", Camera.Parameters::class.java)
        magic.hook(setParameters).intercept { chain ->
            // 只记录尺寸、不替换任何东西，因此不判门控：
            // 门控从关到开时 vSize/pSize 必须已经是最新值
            runCatching {
                val params = chain.args[0] as Camera.Parameters
                vSize = params.previewSize.toSize()
                pSize = params.pictureSize.toSize()
            }.onFailure { Dog.e(TAG, "setParameters record failed: ${it.message}", it, SM.enableLog) }
            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.setPreviewTextureHook() {
        val setPreviewTexture = getDeclaredMethod("setPreviewTexture",
            SurfaceTexture::class.java)
        magic.hook(setPreviewTexture).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            // setPreviewTexture(null) 是合法的解绑调用，强转会 NPE 崩掉目标应用
            val surfaceTexture = chain.args[0] as? SurfaceTexture ?: return@intercept chain.proceed()
            val newArgs = runCatching {
                surfaceTexture.setDefaultBufferSize(vSize.width, vSize.height)
                chain.args.toTypedArray().also { it[0] = Surface(surfaceTexture).gocBlackHoleTexture }
            }.onFailure {
                Dog.e(TAG, "setPreviewTexture failed: ${it.message}", it, SM.enableLog)
            }.getOrNull() ?: return@intercept chain.proceed()
            chain.proceed(newArgs)
        }
    }
    private fun Class<*>.setPreviewDisplayHook() {
        val setPreviewDisplay = getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        magic.hook(setPreviewDisplay).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            // setPreviewDisplay(null) 同样合法，必须走安全转换
            val holder = chain.args[0] as? SurfaceHolder ?: return@intercept chain.proceed()

            val proxy = runCatching {
                @SuppressLint("Recycle")
                val surfaceHolderProxy = Proxy.newProxyInstance(holder.javaClass.classLoader,
                    arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                    if (method.name == "getSurface") return@newProxyInstance holder.surface.gocBlackHole
                    return@newProxyInstance method.invoke(holder, *(args ?: arrayOfNulls<Any>(0)))
                } as SurfaceHolder
                surfaceHolderProxy
            }.onFailure {
                Dog.e(TAG, "setPreviewDisplay failed: ${it.message}", it, SM.enableLog)
            }.getOrNull() ?: return@intercept chain.proceed()

            chain.proceed(arrayOf(proxy))
        }
    }
    private fun Class<*>.setDisplayOrientationHook() {
        val setDisplayOrientation = getDeclaredMethod(
            "setDisplayOrientation",
            Int::class.javaPrimitiveType)

        magic.hook(setDisplayOrientation).intercept { chain ->
            // 同 setParameters：仅记录，不判门控
            runCatching { displayOri = chain.args[0] as Int }
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.startPreviewHook() {
        val startPreview = getDeclaredMethod("startPreview")
        magic.hook(startPreview).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            runCatching {
                val camera = chain.thisObject as Camera
                if (camera.isActiveRef) {
                    SM.rememberCameraBaseData(API, facingFront, sensorOri, displayOri, processName)
                    SM.applyManualRotationToNative()
                    BlackHole.originSurfaces.forEach { surface ->
                        NB.addRenderTarget(surface, vSize.width, vSize.height, pSize.width, pSize.height)
                    }
                    // 先把媒体切到这台相机所属槽再下发
                    SM.activateSlot(LensSlot.ofFront(facingFront))
                    SM.validMedia?.let {
                        val camera3 = Camera3()
                        camera3Map[camera] = camera3
                        camera3.start(magic, it)
                    }
                }
            }.onFailure { Dog.e(TAG, "startPreview failed: ${it.message}", it, SM.enableLog) }
            chain.proceed()
        }
    }
    private fun Class<*>.stopPreviewHook() {
        val stopPreview = getDeclaredMethod("stopPreview")
        magic.hook(stopPreview).intercept { chain ->
            // 清理路径不判门控：运行中关掉开关后 player 仍在播放
            runCatching {
                val camera = chain.thisObject as? Camera
                if (camera != null && camera.isActiveRef) camera3Map[camera]?.pause()
            }.onFailure { Dog.e(TAG, "stopPreview pause failed: ${it.message}", it, SM.enableLog) }
            chain.proceed()
        }
    }

    private fun Class<*>.releaseHook() {
        val release = getDeclaredMethod("release")
        magic.hook(release).intercept { chain ->
            // 清理路径不判门控：漏清理 = Surface/纹理泄漏，
            // 表现为目标应用相机越用越卡直到崩溃
            runCatching {
                val camera = chain.thisObject as? Camera ?: return@runCatching
                if (camera.isActiveRef) {
                    BlackHole.clear()
                    camera3Map[camera]?.stop()
                }
                Dog.w(TAG, "API[1] close camera: ${camera.shortId}", SM.enableLog)
            }.onFailure { Dog.e(TAG, "release cleanup failed: ${it.message}", it, SM.enableLog) }
            chain.proceed()
        }
    }
    private fun Class<*>.onPreviewFrameHook() {
        val onPreviewFrame = getDeclaredMethod("onPreviewFrame",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPreviewFrame).intercept { frame ->
            if (!SM.readyForHook) return@intercept frame.proceed()
            // 与 ImageReader 的 YUV 路径一致：无有效媒体时不覆写，
            // 否则原生引擎没有帧源会把 App 的预览缓冲写成黑帧
            if (SM.validMedia == null) return@intercept frame.proceed()
            runCatching {
                val originBuffer = frame.args[0] as ByteArray
                // 尺寸取 setParameters 记录的预览尺寸：App 的 onPreviewFrame 缓冲
                // 就是按它分配的；原生侧按这个尺寸渲染 NV21 并覆盖
                NB.overwriteYuvBuffer(originBuffer, vSize.width, vSize.height)
            }.onFailure { Dog.e(TAG, "onPreviewFrame overwrite failed: ${it.message}", it, SM.enableLog) }
            frame.proceed()
        }
    }

    private fun Class<*>.setPreviewCallbackHook() {
        val setPreviewCallback = getDeclaredMethod(
            "setPreviewCallback",
            Camera.PreviewCallback::class.java)
        val setPreviewCallbackWithBuffer = getDeclaredMethod(
            "setPreviewCallbackWithBuffer",
            Camera.PreviewCallback::class.java)
        val setOneShotPreviewCallback = getDeclaredMethod("setOneShotPreviewCallback",
            Camera.PreviewCallback::class.java)
        magic.hook(setPreviewCallback).intercept(previewCallbackInterceptor)
        magic.hook(setPreviewCallbackWithBuffer).intercept(previewCallbackInterceptor)
        magic.hook(setOneShotPreviewCallback).intercept(previewCallbackInterceptor)
    }

    private fun Class<*>.onPictureTakenHook() {
        val onPictureTaken = getDeclaredMethod("onPictureTaken",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPictureTaken).intercept { shot ->
            if (!SM.readyForHook) return@intercept shot.proceed()
            val newArgs = shot.args.toTypedArray()
            // 原生编码失败（无帧源 / 未就绪）时返回 null，退回原始相机 JPEG，
            // 绝不能让 App 收到空字节数组或 null
            newArgs[0] = NB.overwriteJPEGBytes() ?: shot.args[0]
            shot.proceed(newArgs)
        }
    }

    private fun Class<*>.takePictureHook() {
        val takePicture = getDeclaredMethod(
            "takePicture",
            Camera.ShutterCallback::class.java,
            Camera.PictureCallback::class.java, // raw
            Camera.PictureCallback::class.java, // post view
            Camera.PictureCallback::class.java) // jpeg

        magic.hook(takePicture).intercept { chain ->
            // 同 previewCallbackInterceptor：这一处只负责把 onPictureTaken 注上，
            // 覆写本身的门控在 onPictureTaken 里。整体 runCatching 是铁律2：
            // 这里强转失败绝不能把异常抛回目标应用的 takePicture
            if (SM.appEnabled) runCatching {
                chain.args[3]?.let { cb ->
                    val clazz = (cb as Camera.PictureCallback).javaClass
                    clazz.safeHook { onPictureTakenHook() }
                }
            }.onFailure { Dog.e(TAG, "takePicture hook install failed: ${it.message}", it, SM.enableLog) }
            chain.proceed()
        }
    }
}
