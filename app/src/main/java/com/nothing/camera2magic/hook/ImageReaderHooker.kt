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

            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size, opts)
            val originalW = opts.outWidth
            val originalH = opts.outHeight

            // Check cache first
            val cacheKey = "${validMedia.file}_${originalW}_${originalH}"
            var jpeg: ByteArray? = if (cacheKey == cachedJpegKey) cachedJpegBytes else null

            if (jpeg != null) {
                Dog.i(TAG, "Using cached JPEG, size=${jpeg.size}", SM.enableLog)
            } else if (originalW > 0 && originalH > 0) {
                val pfd = runCatching { magic.openRemoteFile(validMedia.file) }.getOrNull()
                if (pfd != null) {
                    val replBytes = runCatching {
                        java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                    }.getOrNull()
                    runCatching { pfd.close() }
                    if (replBytes != null && replBytes.isNotEmpty()) {
                        val replBmp = android.graphics.BitmapFactory.decodeByteArray(replBytes, 0, replBytes.size)
                        if (replBmp != null) {
                            val scaled = android.graphics.Bitmap.createScaledBitmap(replBmp, originalW, originalH, true)
                            replBmp.recycle()
                            val bos = java.io.ByteArrayOutputStream()
                            if (SM.compressJpeg) {
                                // Binary search for quality that matches original JPEG size
                                var bestJpeg = byteArrayOf()
                                var bestDiff = Int.MAX_VALUE
                                var lo = 60
                                var hi = 100
                                while (lo <= hi) {
                                    val mid = (lo + hi) / 2
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, mid, bos)
                                    val bytes = bos.toByteArray()
                                    bos.reset()
                                    val diff = bytes.size - originalJpeg.size
                                    if (Math.abs(diff) < Math.abs(bestDiff)) {
                                        bestJpeg = bytes
                                        bestDiff = diff
                                    }
                                    if (diff < 0) lo = mid + 1 else hi = mid - 1
                                }
                                bos.write(bestJpeg)
                            } else {
                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bos)
                            }
                            scaled.recycle()
                            val compressed = bos.toByteArray()

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
                                    "Orientation", "Software",
                                    "PixelXDimension", "PixelYDimension"
                                )) {
                                    runCatching {
                                        val v = origExif.getAttribute(tag)
                                        if (v != null) newExif.setAttribute(tag, v)
                                    }
                                }
                                newExif.saveAttributes()
                                jpeg = tmpFile.readBytes()
                                cachedJpegKey = cacheKey
                                cachedJpegBytes = jpeg
                                Dog.i(TAG, "JPEG replaced with EXIF, size=${jpeg.size}", SM.enableLog)
                            } finally {
                                runCatching { tmpFile.delete() }
                            }
                        }
                    }
                }
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
            handleImage(image)
            return@intercept image
        }
    }

    private fun Class<*>.acquireLatestImageHook() {
        val acquireLatestImage = getDeclaredMethod("acquireLatestImage")
        magic.hook(acquireLatestImage).intercept { chain ->
            Dog.w(TAG, "HOOK: acquireLatestImage called", true)
            val image = chain.proceed() as? Image ?: return@intercept null
            handleImage(image)
            return@intercept image
        }
    }

    private fun Class<*>.acquireNextImageNoThrowISEHook() {
        val acquireNextImageNoThrowISE = getDeclaredMethod("acquireNextImageNoThrowISE")
        magic.hook(acquireNextImageNoThrowISE).intercept { chain ->
            Dog.w(TAG, "HOOK: acquireNextImageNoThrowISE called", true)
            val image = chain.proceed() as? Image ?: return@intercept null
            handleImage(image)
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