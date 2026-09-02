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
        // 单条缓存：key = "file_size_mtime_W_H" -> ByteArray。
        // 带上 size/mtime 是为了媒体重新上传后不命中旧缓存；fstat 失败会退化成
        // "file_null_null_W_H"，同名换图有误命中风险。只存一条，交替分辨率会反复失效。
        @Volatile
        private var cachedJpegKey: String? = null
        @Volatile
        private var cachedJpegBytes: ByteArray? = null

        fun invalidateCache() {
            cachedJpegKey = null
            cachedJpegBytes = null
        }

        // EXIF Orientation tag → 显示所需顺时针角度（6=顺90、3=180、8=逆90）。
        // 镜像类 2/4/5/7 极罕见，与主流消费方一致按 0 处理
        private fun orientationDegrees(tag: String?): Int = when (tag) {
            "3" -> 180
            "6" -> 90
            "8" -> 270
            else -> 0
        }
    }

    init {
        // 铁律3：静态类走 classLoader.safeHook，类不存在只记警告；
        // 直接 loadClass 会把异常抛回 onPackageReady，连带拖挂其余 Hooker 的装配。
        param.classLoader.safeHook(IMAGE_READER_CLASS) {
            newInstance4Hook()
            newInstance5Hook()
            acquireNextImageHook()
            acquireLatestImageHook()
            acquireNextImageNoThrowISEHook()
        }
    }

    private fun handleImage(image: Image): Image {
        // 铁律1：替换路径先判门控，未启用直接返回原图
        if (!SM.readyForHook) return image

        val format = image.format
        Dog.i(TAG, "handleImage format=$format", SM.enableLog)

        if (format == 256) {
            Dog.i(TAG, "app wanna take a picture.", SM.enableLog)
            val validMedia = SM.validMedia
            if (validMedia == null) {
                Dog.i(TAG, "No valid media set, returning original image", SM.enableLog)
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
                            // 相机帧 EXIF：HAL 按每次拍摄的 JPEG_ORIENTATION 写入，设备横竖屏
                            // 变化后同一媒体需要不同的预旋转，因此方向必须参与缓存键
                            val origExif = ExifInterface(java.io.ByteArrayInputStream(originalJpeg))
                            val camDeg = orientationDegrees(origExif.getAttribute("Orientation"))
                            // 缓存键包含文件大小与修改时间：媒体重新上传后不会命中旧缓存
                            val st = runCatching { android.system.Os.fstat(pfd.fileDescriptor) }.getOrNull()
                            val cacheKey = "${validMedia.file}_${st?.st_size}_${st?.st_mtime}_${originalW}_${originalH}_$camDeg"
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
                                        // 方向语义：JPEG 面像素被 App 当作「传感器方向帧」消费——
                                        // 显示旋转由帧内 EXIF（camDeg）驱动，替换像素必须预旋转到
                                        // 同一语义：P' = CW(mediaDeg - camDeg)(媒体像素)，App 按
                                        // camDeg 转回后恰好得到媒体正向画面。实测（星河水印相机
                                        // postRotate(EXIF)）：不预旋转照片恒歪 90°，且 CameraX 的
                                        // on-disk 补齐逻辑会把缺省 Orientation 按元数据转回，
                                        // EXIF 侧无解。EXIF 仍继承相机帧——与预旋转后的像素配套，
                                        // 对其他按标准语义消费 JPEG 的 App 同样自洽。
                                        val mediaDeg = orientationDegrees(
                                            runCatching {
                                                ExifInterface(java.io.ByteArrayInputStream(replBytes)).getAttribute("Orientation")
                                            }.getOrNull()
                                        )
                                        val preRotate = mediaDeg - camDeg
                                        Dog.i(TAG, "orientation diag: camera=$camDeg media=$mediaDeg preRotate=$preRotate buf=${originalW}x${originalH}", SM.enableLog)
                                        val oriented = if (preRotate % 360 != 0) {
                                            val matrix = android.graphics.Matrix().apply { postRotate(preRotate.toFloat()) }
                                            android.graphics.Bitmap.createBitmap(replBmp, 0, 0, replBmp.width, replBmp.height, matrix, true)
                                        } else {
                                            replBmp
                                        }
                                        if (oriented !== replBmp) {
                                            replBmp.recycle()
                                        }
                                        val scaled = android.graphics.Bitmap.createScaledBitmap(oriented, originalW, originalH, true)
                                        // 修复：当替换图与相机输出同尺寸时，createScaledBitmap 会直接返回原不可变位图，
                                        // 此时 scaled 与 oriented 是同一对象，不能重复 recycle，否则后续 compress 会抛
                                        // "Can't compress a recycled bitmap"
                                        if (scaled !== oriented) {
                                            oriented.recycle()
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
                                        // Orientation 继承相机帧：替换像素已预旋转到
                                        // 「传感器方向帧」语义（见上），两者配套后
                                        // App 按该值旋转显示即为媒体正向画面
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
                // 写入后必须恢复与原生相机帧等价的 buffer 状态：limit 保持 capacity、
                // position 归零、未覆盖尾部零填充。CameraX 按 capacity() 消费 JPEG 面
                // （rewind() 后 get(byte[capacity])），把 limit 缩小到 jpeg.size 会让
                // remaining() < capacity 直接 BufferUnderflowException（App 报拍照失败）；
                // 零填充尾部防止「从末尾找 EOI 推尺寸」的解析器误用原相机的残留字节。
                while (buffer.hasRemaining()) buffer.put(0)
                buffer.position(0)
                Dog.i(TAG, "JPEG overwritten via buffer, jpeg=$limit capacity=${buffer.capacity()}", SM.enableLog)
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
                    val written = minOf(jpeg.size, buffer.capacity())
                    for (i in 0 until written) {
                        putByte.invoke(unsafe, address + i, jpeg[i])
                    }
                    // 与常规路径对齐：limit 保持 capacity、position 归零；若 buffer API
                    // 仍不可用（通常正是走到这条兜底的原因）则放弃尾部清零，只保住写入结果
                    runCatching {
                        buffer.limit(buffer.capacity())
                        buffer.position(written)
                        while (buffer.hasRemaining()) buffer.put(0)
                        buffer.position(0)
                    }
                    Dog.i(TAG, "JPEG overwritten via Unsafe, size=$written", SM.enableLog)
                } catch (e2: Exception) {
                    Dog.e(TAG, "Unsafe also failed: ${e2.message}", e2, true)
                }
            }
            return image
        }

        // 与 format 256 保持一致：无有效媒体时不替换画面，
        // 否则原生引擎没有帧源，会把 App 的 YUV 分析面覆写成黑帧/陈旧帧
        if (format == 35 && SM.validMedia != null) handleFormat35(image)

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
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, true) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireLatestImageHook() {
        val acquireLatestImage = getDeclaredMethod("acquireLatestImage")
        magic.hook(acquireLatestImage).intercept { chain ->
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, true) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireNextImageNoThrowISEHook() {
        val acquireNextImageNoThrowISE = getDeclaredMethod("acquireNextImageNoThrowISE")
        magic.hook(acquireNextImageNoThrowISE).intercept { chain ->
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
