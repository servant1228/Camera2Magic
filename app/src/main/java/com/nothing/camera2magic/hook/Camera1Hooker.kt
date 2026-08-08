@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.annotation.SuppressLint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.media.ExifInterface
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture

import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import android.util.Size
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.hook.BlackHole.gocBlackHole
import com.nothing.camera2magic.hook.BlackHole.gocBlackHoleTexture

@SuppressLint("Recycle")
class Camera1Hooker(val magic: MagicHook, param: PackageReadyParam) : HookManager  {
    companion object {
        private const val TAG = "[CAM1]"
        private const val CLS_CAMERA = "android.hardware.Camera"
        private var activatedCamera = WeakReference<Any>(null)

        private val camera3Map = WeakHashMap<Any, Camera3>()
        ///////////
        private const val API = 1
        private var facingFront = false
        private var sensorOri = 0
        private var displayOri = 0
        private val processName: String
            get() = GlobalState.processName

        private var vSize = Size(0, 0)
        private var pSize = Size(0, 0)
        @Volatile
        private var lastTakePictureAt = 0L
        @Volatile
        private var lastPreviewBufferSize = -1
        /////////////
        private val Camera.isActiveRef: Boolean
            get() = activatedCamera.get() == this
    }

    private val openInterceptor: (Chain) -> Any? = intercept@{ chain ->
        val camera = chain.proceed() as? Camera ?: return@intercept null
        if (!SM.readyForHook) return@intercept camera
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
        Dog.w(TAG, "API[1] open camera: ${camera.shortId}", SM.enableLog)
        return@intercept camera
    }

    private val previewCallbackInterceptor: (Chain) -> Any? = intercept@ { chain ->
        if (!SM.readyForHook) return@intercept chain.proceed()
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
            addCallbackBufferHook()
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
            val params = chain.args[0] as Camera.Parameters
            vSize = params.previewSize.toSize()
            pSize = params.pictureSize.toSize()
            return@intercept chain.proceed()
        }
    }
    private fun Class<*>.setPreviewTextureHook() {
        val setPreviewTexture = getDeclaredMethod("setPreviewTexture",
            SurfaceTexture::class.java)
        magic.hook(setPreviewTexture).intercept { chain ->
            val surfaceTexture = chain.args[0] as SurfaceTexture
            Dog.i(TAG, "setPreviewTexture: ${surfaceTexture.shortId}, vSize=${vSize.width}x${vSize.height}", SM.enableLog)
            if (!SM.readyForHook) return@intercept chain.proceed()
            surfaceTexture.setDefaultBufferSize(vSize.width, vSize.height)

            val newArgs = chain.args.toTypedArray()
            newArgs[0] = Surface(surfaceTexture).gocBlackHoleTexture
            chain.proceed(newArgs)
        }
    }
    private fun Class<*>.setPreviewDisplayHook() {
        val setPreviewDisplay = getDeclaredMethod(
            "setPreviewDisplay",
            SurfaceHolder::class.java)
        magic.hook(setPreviewDisplay).intercept { chain ->
            val holder = chain.args[0] as SurfaceHolder
            Dog.i(TAG, "setPreviewDisplay: ${holder.shortId}", SM.enableLog)
            if (!SM.readyForHook) return@intercept chain.proceed()

            @SuppressLint("Recycle")
            val surfaceHolderProxy = Proxy.newProxyInstance(holder.javaClass.classLoader,
                arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                if (method.name == "getSurface") return@newProxyInstance holder.surface.gocBlackHole
                return@newProxyInstance method.invoke(holder, *(args ?: arrayOfNulls<Any>(0)))
            } as SurfaceHolder

            chain.proceed(arrayOf(surfaceHolderProxy))
        }
    }
    private fun Class<*>.setDisplayOrientationHook() {
        val setDisplayOrientation = getDeclaredMethod(
            "setDisplayOrientation",
            Int::class.javaPrimitiveType)

        magic.hook(setDisplayOrientation).intercept { chain ->
            displayOri = chain.args[0] as Int
            return@intercept chain.proceed()
        }
    }

    private fun Class<*>.startPreviewHook() {
        val startPreview = getDeclaredMethod("startPreview")
        magic.hook(startPreview).intercept { chain ->
            val camera = chain.thisObject as Camera
            Dog.i(TAG, "startPreview: ${camera.shortId}, active=${camera.isActiveRef}, vSize=${vSize.width}x${vSize.height}, pSize=${pSize.width}x${pSize.height}, surfaces=${BlackHole.originSurfaces.size}", SM.enableLog)
            if (!SM.readyForHook) return@intercept chain.proceed()
            if (camera.isActiveRef) {
                val t0 = SystemClock.elapsedRealtime()
                SM.rememberCameraBaseData(API, facingFront, sensorOri, displayOri, processName)
                SM.applyManualRotationToNative()
                BlackHole.originSurfaces.forEach { surface ->
                    NB.addRenderTarget(surface, vSize.width, vSize.height, pSize.width, pSize.height)
                }
                SM.validMedia?.let {
                    val camera3 = Camera3()
                    camera3Map[camera] = camera3
                    camera3.start(magic, it)
                }
                Dog.i(TAG, "startPreview setup done in ${SystemClock.elapsedRealtime() - t0}ms", SM.enableLog)
            }
            chain.proceed()
        }
    }
    private fun Class<*>.stopPreviewHook() {
        val stopPreview = getDeclaredMethod("stopPreview")
        magic.hook(stopPreview).intercept { chain ->
            val camera = chain.thisObject as Camera
            Dog.i(TAG, "stopPreview: ${camera.shortId}, active=${camera.isActiveRef}", SM.enableLog)
            if (!SM.readyForHook) return@intercept chain.proceed()
            if (camera.isActiveRef) camera3Map[camera]?.pause()
            chain.proceed()
        }
    }

    private fun Class<*>.releaseHook() {
        val release = getDeclaredMethod("release")
        magic.hook(release).intercept { chain ->
            if (!SM.readyForHook) return@intercept chain.proceed()
            val camera = chain.thisObject as Camera
            if (camera.isActiveRef) {
                BlackHole.clear()
                camera3Map[camera]?.stop()
            }
            Dog.w(TAG, "API[1] close camera: ${camera.shortId}", SM.enableLog)
            chain.proceed()
        }
    }
    private fun Class<*>.onPreviewFrameHook() {
        val onPreviewFrame = getDeclaredMethod("onPreviewFrame",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPreviewFrame).intercept { frame ->
            if (!SM.readyForHook) return@intercept frame.proceed()
            val originBuffer = frame.args[0] as ByteArray
            if (originBuffer.size != lastPreviewBufferSize) {
                lastPreviewBufferSize = originBuffer.size
                Dog.i(TAG, "onPreviewFrame: bufferSize=${originBuffer.size}, camera=${(frame.args.getOrNull(1) as? Camera)?.shortId}", SM.enableLog)
            }
            NB.overwriteYuvBuffer(originBuffer)
            frame.proceed()
        }
    }

    /**
     * 「照片方向修正」开启时，Camera1 拍照片改走独立 JPEG 生成：
     * 原生 overwriteJPEGBytes 直接编码当前预览纹理帧，不受参数切换影响，拍出来是扁的。
     * 这里从媒体文件重新生成：旋转烘焙与「照片方向修正」开关（main_fix_photo_rotation）联动，
     * 缩放到原相机照片尺寸、保留相机 EXIF。
     */
    private fun replaceJpegWithImage(origin: ByteArray?): ByteArray? {
        if (!SM.fixPhotoRotation) return null
        val validMedia = SM.validMedia ?: return null
        if (validMedia.type != MagicType.LOCAL_IMAGE) return null
        if (origin == null || origin.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(origin, 0, origin.size, bounds)
            val originalW = bounds.outWidth
            val originalH = bounds.outHeight
            if (originalW <= 0 || originalH <= 0) return@runCatching null

            val camOrientation = if (SM.fixPhotoRotation) {
                runCatching {
                    ExifInterface(ByteArrayInputStream(origin))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } else ExifInterface.ORIENTATION_NORMAL

            val pfd = magic.openRemoteFile(validMedia.file)
            try {
                val mediaBytes = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                val bakeDegrees = orientationDegrees(camOrientation)
                val replBmp = decodeSampled(mediaBytes, 2048) ?: return@runCatching null
                val scaled = bakeOrientation(replBmp, bakeDegrees, originalW, originalH)
                Dog.i(
                    TAG,
                    "image replace: origin=${originalW}x${originalH} camOri=$camOrientation, media=${replBmp.width}x${replBmp.height}, out=${scaled.width}x${scaled.height}, bake=$bakeDegrees, fixRotation=${SM.fixPhotoRotation}",
                    SM.enableLog,
                )
                if (scaled !== replBmp) replBmp.recycle()

                // 贴近原相机 JPEG 大小，避免触发 App 的照片大小校验
                val target = maxOf(64 * 1024, origin.size - 16 * 1024)
                var bestJpeg = byteArrayOf()
                var bestDiff = Int.MAX_VALUE
                val bos = java.io.ByteArrayOutputStream()
                var lo = if (SM.compressJpeg) 60 else 85
                var hi = 100
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    bos.reset()
                    scaled.compress(Bitmap.CompressFormat.JPEG, mid, bos)
                    val bytes = bos.toByteArray()
                    val diff = bytes.size - target
                    if (Math.abs(diff) < Math.abs(bestDiff)) {
                        bestJpeg = bytes
                        bestDiff = diff
                    }
                    if (diff < 0) lo = mid + 1 else hi = mid - 1
                }
                scaled.recycle()

                // 保留原相机 EXIF（含 Orientation），查看器按 EXIF 旋转后即为正向画面
                var jpeg = bestJpeg
                val tmpFile = java.io.File(GlobalState.appContext.cacheDir, "cam2magic_cam1_tmp.jpg")
                try {
                    tmpFile.writeBytes(bestJpeg)
                    val origExif = ExifInterface(ByteArrayInputStream(origin))
                    val newExif = ExifInterface(tmpFile.absolutePath)
                    for (tag in listOf(
                        "Make", "Model", "DateTime", "DateTimeOriginal",
                        "FNumber", "FocalLength", "Flash", "WhiteBalance",
                        "ISOSpeedRatings", "ExposureTime", "ApertureValue",
                        "GPSLatitude", "GPSLongitude", "GPSLatitudeRef", "GPSLongitudeRef",
                        "Software", "PixelXDimension", "PixelYDimension", "Orientation"
                    )) {
                        runCatching {
                            val v = origExif.getAttribute(tag)
                            if (v != null) newExif.setAttribute(tag, v)
                        }
                    }
                    newExif.saveAttributes()
                    val withExif = tmpFile.readBytes()
                    if (withExif.size <= origin.size * 2) jpeg = withExif
                } finally {
                    runCatching { tmpFile.delete() }
                }
                jpeg
            } finally {
                runCatching { pfd.close() }
            }
        }.getOrNull()
    }

    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun orientationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> -90f   // 6
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f  // 3
        ExifInterface.ORIENTATION_ROTATE_270 -> 90f   // 8
        else -> 0f
    }

    /** 等比适配（contain，黑边）到目标尺寸，并把旋转烘焙进像素。 */
    private fun bakeOrientation(
        src: Bitmap,
        degrees: Float,
        targetW: Int,
        targetH: Int,
    ): Bitmap {
        if (degrees == 0f) {
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // 等比适配（contain，黑边）
        val scale = minOf(targetW.toFloat() / rotated.width, targetH.toFloat() / rotated.height)
        val nw = (rotated.width * scale).toInt().coerceAtLeast(1)
        val nh = (rotated.height * scale).toInt().coerceAtLeast(1)
        val fitted = Bitmap.createScaledBitmap(rotated, nw, nh, true)
        rotated.recycle()
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(fitted, (targetW - nw) / 2f, (targetH - nh) / 2f, null)
        fitted.recycle()
        return result
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

    private fun Class<*>.addCallbackBufferHook() {
        /*
        val addCallbackBuffer = getDeclaredMethod("addCallbackBuffer",
            ByteArray::class.java)

        magic.hook(addCallbackBuffer).intercept { chain ->
            val buffer = chain.args[0] as ByteArray
            Dog.i(TAG, "add buffer[${buffer.size}]", SM.enableLog)
            chain.proceed()
        }

        */
    }

    private fun Class<*>.onPictureTakenHook() {
        val onPictureTaken = getDeclaredMethod("onPictureTaken",
            ByteArray::class.java, Camera::class.java)
        magic.hook(onPictureTaken).intercept { shot ->
            val camera = shot.args.getOrNull(1) as? Camera
            val originSize = (shot.args.getOrNull(0) as? ByteArray)?.size ?: -1
            Dog.w(TAG, "onPictureTaken: ${camera?.shortId}, originJpegSize=$originSize", SM.enableLog)
            if (!SM.readyForHook) return@intercept shot.proceed()
            val t0 = SystemClock.elapsedRealtime()
            val newArgs = shot.args.toTypedArray()
            val replaced = replaceJpegWithImage(shot.args.getOrNull(0) as? ByteArray)
            if (replaced != null) {
                newArgs[0] = replaced
                Dog.w(TAG, "image JPEG replaced in ${SystemClock.elapsedRealtime() - t0}ms, newJpegSize=${replaced.size}, total=${SystemClock.elapsedRealtime() - lastTakePictureAt}ms", SM.enableLog)
            } else {
                // 非图片媒体（视频/RTSP）仍走原生 JPEG
                newArgs[0] = NB.overwriteJPEGBytes()
                val newSize = (newArgs.getOrNull(0) as? ByteArray)?.size ?: -1
                Dog.w(TAG, "native overwriteJPEGBytes done in ${SystemClock.elapsedRealtime() - t0}ms, newJpegSize=$newSize, total=${SystemClock.elapsedRealtime() - lastTakePictureAt}ms", SM.enableLog)
            }
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
            val camera = chain.thisObject as Camera
            lastTakePictureAt = SystemClock.elapsedRealtime()
            Dog.w(TAG, "takePicture: ${camera.shortId}, active=${camera.isActiveRef}, shutter=${chain.args.getOrNull(0) != null}, raw=${chain.args.getOrNull(1) != null}, postView=${chain.args.getOrNull(2) != null}, jpeg=${chain.args.getOrNull(3) != null}, media=${SM.validMedia?.file ?: "null"}", SM.enableLog)
            if (!SM.readyForHook) return@intercept chain.proceed()
            chain.args[3]?.let { cb ->
                val clazz = (cb as Camera.PictureCallback).javaClass
                clazz.safeHook { onPictureTakenHook() }
            }
            chain.proceed()
        }
    }
}
