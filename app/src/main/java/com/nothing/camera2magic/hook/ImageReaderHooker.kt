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
                            val cacheKey = "${validMedia.file}_${st?.st_size}_${st?.st_mtime}_${originalW}_${originalH}"
                            jpeg = if (cacheKey == cachedJpegKey) cachedJpegBytes else null
                            if (jpeg != null) {
                                Dog.i(TAG, "Using cached JPEG, size=${jpeg.size}", SM.enableLog)
                            } else {
                                val replBytes = runCatching {
                                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                }.getOrNull()
                                if (replBytes != null && replBytes.isNotEmpty()) {
                                    val replBmp = android.graphics.BitmapFactory.decodeByteArray(replBytes, 0, replBytes.size)
                                    if (replBmp != null) {
                                        val scaled = android.graphics.Bitmap.createScaledBitmap(replBmp, originalW, originalH, true)
                                        // 修复：当替换图与相机输出同尺寸时，createScaledBitmap 会直接返回原不可变位图，
                                        // 此时 scaled 与 replBmp 是同一对象，不能重复 recycle，否则后续 compress 会抛
                                        // "Can't compress a recycled bitmap"
                                        if (scaled !== replBmp) {
                                            replBmp.recycle()
                                        }
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
                                        // 保留原相机 EXIF Orientation（通常 6=顺时针90°），
                                        // 查看器按 EXIF 旋转后即为正向画面
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
}
