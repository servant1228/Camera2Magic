package com.nothing.camera2magic.hook

import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap

class ImageReaderHooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[ImageReader]"
        private const val IMAGE_READER_CLASS = "android.media.ImageReader"
        // Cache: key = "file_w_h" -> ByteArray
        @Volatile
        private var cachedJpegKey: String? = null
        @Volatile
        private var cachedJpegBytes: ByteArray? = null

        fun invalidateCache() {
            cachedJpegKey = null
            cachedJpegBytes = null
        }
    }

    init {
        val classLoader = param.classLoader
        val irClass = classLoader.loadClass(IMAGE_READER_CLASS)
        irClass.safeHook {
            newInstance4Hook()
            newInstance5Hook()
            acquireNextImageHook()
            acquireLatestImageHook()
            acquireNextImageNoThrowISEHook()
        }
    }

    private fun handleImage(image: Image): Image {
        val format = image.format
        Dog.w(TAG, "HOOK: handleImage format=$format", true)

        if (format == 256) {
            Dog.w(TAG, "app wanna take a picture.")
            val validMedia = SM.validMedia
            if (validMedia == null) {
                Dog.e(TAG, "No valid media set, returning original image", null, true)
                return image
            }
            val buffer = image.planes[0].buffer
            val originalSize = buffer.remaining()
            val originalJpeg = ByteArray(originalSize)
            val savedPos = buffer.position()
            buffer.get(originalJpeg)
            buffer.position(savedPos)

            var jpeg: ByteArray? = null
            // 替换逻辑整体兜底：任何异常都不阻断原调用，退回原图并记录日志
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size, opts)
                val originalW = opts.outWidth
                val originalH = opts.outHeight
                if (originalW > 0 && originalH > 0) {
                    val pfd = runCatching { magic.openRemoteFile(validMedia.file) }.getOrNull()
                    if (pfd != null) {
                        try {
                            // 缓存键包含文件大小与修改时间：媒体重新上传后不会命中旧缓存
                            val st = runCatching { android.system.Os.fstat(pfd.fileDescriptor) }.getOrNull()
                            val cacheKey = "${validMedia.file}_${if (SM.fixPhotoRotation) 1 else 0}_${st?.st_size}_${st?.st_mtime}_${originalW}_${originalH}"
                            jpeg = if (cacheKey == cachedJpegKey) cachedJpegBytes else null
                            if (jpeg != null) {
                                Dog.i(TAG, "Using cached JPEG, size=${jpeg.size}", SM.enableLog)
                            } else {
                                val replBytes = runCatching {
                                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                }.getOrNull()
                                if (replBytes != null && replBytes.isNotEmpty()) {
                                    val camOrientation = if (SM.fixPhotoRotation) {
                                        runCatching {
                                            ExifInterface(java.io.ByteArrayInputStream(originalJpeg))
                                                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                                        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                                    } else ExifInterface.ORIENTATION_NORMAL
                                    val replBmp = if (SM.fixPhotoRotation) {
                                        decodeSampled(replBytes, 2048)
                                    } else {
                                        android.graphics.BitmapFactory.decodeByteArray(replBytes, 0, replBytes.size)
                                    }
                                    if (replBmp != null) {
                                        val bakeDegrees = orientationDegrees(camOrientation)
                                        val scaled = if (SM.fixPhotoRotation) {
                                            Dog.i(
                                                TAG,
                                                "photo fix: camOri=$camOrientation bake=$bakeDegrees src=${replBmp.width}x${replBmp.height} out=${originalW}x${originalH}",
                                                true,
                                            )
                                            runCatching { bakeOrientation(replBmp, bakeDegrees, originalW, originalH) }
                                                .getOrElse {
                                                    Dog.e(TAG, "bake orientation failed: ${it.message}", it, true)
                                                    android.graphics.Bitmap.createScaledBitmap(replBmp, originalW, originalH, true)
                                                }
                                        } else {
                                            android.graphics.Bitmap.createScaledBitmap(replBmp, originalW, originalH, true)
                                        }
                                        replBmp.recycle()
                                        val bos = java.io.ByteArrayOutputStream()
                                        val cap = buffer.capacity()
                                        val target = minOf(originalJpeg.size, cap)
                                        // 尽量贴近原相机 JPEG 大小（EXIF 段通常仅几 KB，预留 16KB 即可），
                                        // 避免文件偏小触发 App 的照片大小校验失败
                                        val searchTarget = maxOf(64 * 1024, target - 16 * 1024)
                                        var compressed: ByteArray
                                        // 始终按原图大小匹配质量：既避免超出缓冲区被截断，也贴近 App 的尺寸/大小校验
                                        var bestJpeg = byteArrayOf()
                                        var bestDiff = Int.MAX_VALUE
                                        // 不启用智能压缩，保底 85 以保证输出画质
                                        var lo = 85
                                        var hi = 100
                                        while (lo <= hi) {
                                            val mid = (lo + hi) / 2
                                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, mid, bos)
                                            val bytes = bos.toByteArray()
                                            bos.reset()
                                            val diff = bytes.size - searchTarget
                                            if (Math.abs(diff) < Math.abs(bestDiff)) {
                                                bestJpeg = bytes
                                                bestDiff = diff
                                            }
                                            if (diff < 0) lo = mid + 1 else hi = mid - 1
                                        }
                                        compressed = bestJpeg
                                        // 硬性兜底：压缩结果绝不能超过缓冲区容量，否则写入时被截断成坏图
                                        if (compressed.size > cap) {
                                            var q = 95
                                            while (q >= 50 && compressed.size > cap) {
                                                bos.reset()
                                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos)
                                                compressed = bos.toByteArray()
                                                q -= 5
                                            }
                                            Dog.w(TAG, "JPEG too large, downscaled to size=${compressed.size}", SM.enableLog)
                                        }
                                        scaled.recycle()

                                        // Preserve original camera EXIF
                                        val tmpFile = java.io.File(GlobalState.appContext.cacheDir, "cam2magic_tmp.jpg")
                                        try {
                                            tmpFile.writeBytes(compressed)
                                            val origExif = ExifInterface(java.io.ByteArrayInputStream(originalJpeg))
                                            val newExif = ExifInterface(tmpFile.getAbsolutePath())
                                            for (tag in listOf(
                                                "Make", "Model", "DateTime", "DateTimeOriginal",
                                                "FNumber", "FocalLength", "Flash", "WhiteBalance",
                                                "ISOSpeedRatings", "ExposureTime", "ApertureValue",
                                                "GPSLatitude", "GPSLongitude", "GPSLatitudeRef", "GPSLongitudeRef",
                                                "Software",
                                                "PixelXDimension", "PixelYDimension"
                                            )) {
                                                runCatching {
                                                    val v = origExif.getAttribute(tag)
                                                    if (v != null) newExif.setAttribute(tag, v)
                                                }
                                            }
                                        // 保留原相机 EXIF Orientation（通常 6=顺时针90°）：
                                        // 修正模式已把旋转烘焙进像素，方向最终正确；
                                        // 且 App 的水印逻辑按竖拍方向处理，只画一次
                                        runCatching {
                                            val v = origExif.getAttribute("Orientation")
                                            if (v != null) newExif.setAttribute("Orientation", v)
                                        }
                                            newExif.saveAttributes()
                                            jpeg = tmpFile.readBytes()
                                            // 最终文件仍可能因 EXIF 段超容量：此时放弃 EXIF，保证照片完整可解码
                                            if (jpeg.size > cap) {
                                                Dog.w(TAG, "EXIF pushed JPEG over buffer, dropping EXIF: ${jpeg.size} > $cap", true)
                                                jpeg = compressed
                                            }
                                            cachedJpegKey = cacheKey
                                            cachedJpegBytes = jpeg
                                            Dog.i(TAG, "JPEG replaced with EXIF, size=${jpeg.size}", SM.enableLog)
                                        } finally {
                                            runCatching { tmpFile.delete() }
                                        }
                                    }
                                }
                            }
                        } finally {
                            runCatching { pfd.close() }
                        }
                    }
                }
            }.onFailure { e ->
                Dog.e(TAG, "JPEG replacement failed: ${e.message}", e, true)
            }
            if (jpeg == null || jpeg.isEmpty()) {
                Dog.e(TAG, "Failed to get replacement JPEG for format 256", null, true)
                return image
            }
            try {
                buffer.clear()
                val limit = minOf(jpeg.size, buffer.capacity())
                buffer.put(jpeg, 0, limit)
                buffer.limit(limit)
                Dog.i(TAG, "JPEG overwritten via buffer, size=$limit", SM.enableLog)
            } catch (e: Exception) {
                Dog.w(TAG, "JPEG buffer write failed, trying Unsafe: ${e.message}", SM.enableLog)
                try {
                    var addressField = runCatching { buffer.javaClass.getDeclaredField("address") }.getOrNull()
                    if (addressField == null) {
                        addressField = Class.forName("java.nio.Buffer").getDeclaredField("address")
                    }
                    addressField.isAccessible = true
                    val address = addressField.getLong(buffer)
                    val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                    val putByte = unsafe.javaClass.getMethod("putByte", Long::class.java, Byte::class.java)
                    for (i in 0 until minOf(jpeg.size, buffer.capacity())) {
                        putByte.invoke(unsafe, address + i, jpeg[i])
                    }
                    Dog.i(TAG, "JPEG overwritten via Unsafe, size=${minOf(jpeg.size, buffer.capacity())}", SM.enableLog)
                } catch (e2: Exception) {
                    Dog.e(TAG, "Unsafe also failed: ${e2.message}", e2, true)
                }
            }
            return image
        }

        if (format == 35) handleFormat35(image)

        return image
    }

    private fun Class<*>.newInstance4Hook() { // 4 参数版本
        val newInstance = getDeclaredMethod("newInstance",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java)
        magic.hook(newInstance).intercept { chain ->
            val reader = chain.proceed() as ImageReader
            Dog.i(TAG, "[:newInstance] 4 args, ${reader.surface.shortId} format: ${reader.imageFormat}, size: ${reader.width}x${reader.height}", SM.enableLog)
            return@intercept reader
        }
    }
    private fun Class<*>.newInstance5Hook() { // 5 参数版本
        val newInstance = getDeclaredMethod("newInstance",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Long::class.java)
        magic.hook(newInstance).intercept { chain ->
            val reader = chain.proceed() as ImageReader
            val args = chain.args
            Dog.i(TAG, "[:newInstance] 5 args, ${reader.surface.shortId}, format: ${args[2]}, size: ${args[0]}x${args[1]}", SM.enableLog)
            return@intercept reader
        }
    }

    private fun Class<*>.acquireNextImageHook() {
        val acquireNextImage = getDeclaredMethod("acquireNextImage")
        magic.hook(acquireNextImage).intercept { chain ->
            Dog.w(TAG, "HOOK: acquireNextImage called", true)
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, true) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireLatestImageHook() {
        val acquireLatestImage = getDeclaredMethod("acquireLatestImage")
        magic.hook(acquireLatestImage).intercept { chain ->
            Dog.w(TAG, "HOOK: acquireLatestImage called", true)
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, true) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireNextImageNoThrowISEHook() {
        val acquireNextImageNoThrowISE = getDeclaredMethod("acquireNextImageNoThrowISE")
        magic.hook(acquireNextImageNoThrowISE).intercept { chain ->
            Dog.w(TAG, "HOOK: acquireNextImageNoThrowISE called", true)
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, true) }
            return@intercept image
        }
    }

    private fun handleFormat35(image: Image) {
        val yPlane = image.planes[0] // 获取三个平面数据

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uPlane = image.planes[1]
        val uBuffer = uPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vPlane = image.planes[2]
        val vBuffer = vPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        NB.overwriteYuvBuffer(yBuffer, yRowStride, yPixelStride,
            uBuffer, uRowStride, uPixelStride, vBuffer, vRowStride, vPixelStride)
    }

    /** 采样解码，限制最大边长，降低烘焙旋转时的峰值内存。 */
    private fun decodeSampled(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** EXIF Orientation → 需要烘焙进像素的旋转角度（正值 = 顺时针）。 */
    private fun orientationDegrees(orientation: Int): Float = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> -90f   // 6：显示时顺时针 90°，烘焙时逆时针
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f  // 3
        ExifInterface.ORIENTATION_ROTATE_270 -> 90f   // 8：显示时逆时针 90°，烘焙时顺时针
        else -> 0f
    }

    /**
     * 把给定旋转角度“烘焙”进像素并等比适配（contain，黑边）到目标尺寸。
     * 例如目标角度 -90°：内容先逆时针转 90°，App/查看器再按相机 EXIF 转 90° 即恢复正向。
     */
    private fun bakeOrientation(
        src: android.graphics.Bitmap,
        degrees: Float,
        targetW: Int,
        targetH: Int,
    ): android.graphics.Bitmap {
        if (degrees == 0f) {
            return android.graphics.Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // 等比适配（contain，黑边）
        val scale = minOf(targetW.toFloat() / rotated.width, targetH.toFloat() / rotated.height)
        val nw = (rotated.width * scale).toInt().coerceAtLeast(1)
        val nh = (rotated.height * scale).toInt().coerceAtLeast(1)
        val fitted = android.graphics.Bitmap.createScaledBitmap(rotated, nw, nh, true)
        rotated.recycle()
        val result = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(fitted, (targetW - nw) / 2f, (targetH - nh) / 2f, null)
        fitted.recycle()
        return result
    }
}
