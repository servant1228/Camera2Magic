package com.nothing.camera2magic.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
import kotlin.math.ln
import kotlin.math.roundToInt

class ImageReaderHooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {

    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[ImageReader]"
        private const val IMAGE_READER_CLASS = "android.media.ImageReader"
        // 替换帧缓存：key = "file_size_mtime_W_H_camDeg" -> ByteArray。
        // 带上 size/mtime 是为了媒体重新上传后不命中旧缓存；fstat 失败会退化成
        // "file_null_null_W_H"，同名换图有误命中风险。按字节数限流（而非条数），
        // 避免高清图连着存好几份把目标 App 内存撑爆。
        private val jpegCache = object : android.util.LruCache<String, ByteArray>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }

        fun invalidateCache() {
            jpegCache.evictAll()
        }

        // EXIF Orientation tag → 显示所需顺时针角度（6=顺90、3=180、8=逆90）。
        // 镜像类 2/4/5/7 极罕见，与主流消费方一致按 0 处理
        private fun orientationDegrees(tag: String?): Int = when (tag) {
            "3" -> 180
            "6" -> 90
            "8" -> 270
            else -> 0
        }

        // 质量预估缩略图长边：1024px 时 JPEG 头/量化表开销占比 <2%，
        // 2 次小图编码 + 1 次全尺寸编码即可替代原来的 5~6 次全尺寸编码。
        private const val PREVIEW_LONG_EDGE = 1024
        private const val Q_MIN = 85
        private const val Q_MAX = 100

        private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
            val bos = java.io.ByteArrayOutputStream(512 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            return bos.toByteArray()
        }

        /**
         * 用小图估算「JPEG 质量 → 文件大小」曲线（按像素数等比折回全尺寸），
         * 再在对数域线性插值求命中 targetBytes 的质量档。
         * 只做两次小图编码，把质量选择的成本从「全尺寸多次」降到「全尺寸一次」。
         */
        private fun estimateJpegQuality(bitmap: Bitmap, targetBytes: Int): Int {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            val previewScale = if (longEdge > PREVIEW_LONG_EDGE) PREVIEW_LONG_EDGE.toFloat() / longEdge else 1f
            val pw = (bitmap.width * previewScale).toInt().coerceAtLeast(1)
            val ph = (bitmap.height * previewScale).toInt().coerceAtLeast(1)
            val preview = if (pw == bitmap.width && ph == bitmap.height) bitmap
                          else Bitmap.createScaledBitmap(bitmap, pw, ph, true)
            val bos = java.io.ByteArrayOutputStream(64 * 1024)
            fun previewSize(q: Int): Long {
                bos.reset()
                preview.compress(Bitmap.CompressFormat.JPEG, q, bos)
                return bos.size().toLong()
            }
            val low = previewSize(Q_MIN)
            val high = previewSize(Q_MAX)
            // createScaledBitmap 在原图尺寸不变时会直接返回源位图，不能误回收
            if (preview !== bitmap) preview.recycle()
            if (low <= 0L || high <= low) return Q_MAX
            val areaRatio = (bitmap.width.toLong() * bitmap.height) / (pw.toLong() * ph)
            val fullLow = low * areaRatio
            val fullHigh = high * areaRatio
            val t = targetBytes.toLong()
            if (t <= fullLow) return Q_MIN
            if (t >= fullHigh) return Q_MAX
            val frac = (ln(t.toDouble()) - ln(fullLow.toDouble())) / (ln(fullHigh.toDouble()) - ln(fullLow.toDouble()))
            return (Q_MIN + frac * (Q_MAX - Q_MIN)).roundToInt().coerceIn(Q_MIN, Q_MAX)
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
            // JPEG 替换只对本地图片有意义：视频 / 网络流没有「一张图」可解，
            // 且网络流的 file 是 URL，传给 openRemoteFile 会去开一个非法文件名。
            // （视频/流的替换帧由 native 引擎在 Camera1 拍照路径与 YUV 路径提供）
            if (validMedia.type != MagicType.LOCAL_IMAGE) {
                Dog.i(TAG, "non-image media (${validMedia.type.label}), keeping original JPEG", SM.enableLog)
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
                            jpeg = jpegCache.get(cacheKey)
                            if (jpeg != null) {
                                Dog.i(TAG, "Using cached JPEG, size=${jpeg.size}", SM.enableLog)
                            } else {
                                val replBytes = runCatching {
                                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                }.getOrNull()
                                if (replBytes != null && replBytes.isNotEmpty()) {
                                    // 媒体原图可能远大于相机帧（如 1 亿像素照片）。先按「解码后长边不超过
                                    // 目标」的最大 2 次幂降采样，避免在目标 App 进程里全尺寸解码 OOM
                                    // （解码量恒定 ≤ 目标，峰值内存可控）；最后一步旋转+缩放再统一到目标尺寸。
                                    val targetLong = maxOf(originalW, originalH)
                                    val replBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    android.graphics.BitmapFactory.decodeByteArray(replBytes, 0, replBytes.size, replBounds)
                                    var sample = 1
                                    while (maxOf(replBounds.outWidth, replBounds.outHeight) / sample > targetLong) sample *= 2
                                    val replOpts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                        inPreferredConfig = Bitmap.Config.ARGB_8888
                                    }
                                    val replBmp = android.graphics.BitmapFactory.decodeByteArray(replBytes, 0, replBytes.size, replOpts)
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
                                        // 旋转 + 缩放到相机帧尺寸，一次 Canvas 画完。原实现先 createBitmap
                                        // 转出一张全图、再 createScaledBitmap 出一张全图，峰值内存是目标图的 3 倍；
                                        // 原图已是目标尺寸且无需旋转时直接复用，连这次拷贝也省掉。
                                        val scaled: Bitmap
                                        if (preRotate % 360 == 0 && replBmp.width == originalW && replBmp.height == originalH) {
                                            scaled = replBmp
                                        } else {
                                            val out = Bitmap.createBitmap(originalW, originalH, Bitmap.Config.ARGB_8888)
                                            val matrix = Matrix()
                                            matrix.postRotate(preRotate.toFloat(), replBmp.width / 2f, replBmp.height / 2f)
                                            val rotW = if (preRotate % 180 == 0) replBmp.width else replBmp.height
                                            val rotH = if (preRotate % 180 == 0) replBmp.height else replBmp.width
                                            matrix.postTranslate((rotW - replBmp.width) / 2f, (rotH - replBmp.height) / 2f)
                                            matrix.postScale(originalW / rotW.toFloat(), originalH / rotH.toFloat())
                                            Canvas(out).drawBitmap(
                                                replBmp, matrix,
                                                Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                                            )
                                            scaled = out
                                        }
                                        if (scaled !== replBmp) replBmp.recycle()

                                        val cap = buffer.capacity()
                                        val target = minOf(originalJpeg.size, cap)
                                        // 尽量贴近原相机 JPEG 大小（EXIF 段通常仅几 KB，预留 16KB 即可），
                                        // 避免文件偏小触发 App 的照片大小校验失败
                                        val searchTarget = maxOf(64 * 1024, target - 16 * 1024)
                                        // 小图预估质量档，全尺寸只编码 1 次（原实现全尺寸要压 5~6 次）
                                        val quality = estimateJpegQuality(scaled, searchTarget)
                                        var compressed = encodeJpeg(scaled, quality)
                                        // 硬性兜底：压缩结果绝不能超过缓冲区容量，否则写入时被截断成坏图
                                        if (compressed.size > cap) {
                                            var q = quality - 4
                                            while (q >= 50 && compressed.size > cap) {
                                                compressed = encodeJpeg(scaled, q)
                                                q -= 4
                                            }
                                            Dog.w(TAG, "JPEG too large, downscaled to size=${compressed.size}", SM.enableLog)
                                        } else if (compressed.size < searchTarget) {
                                            // 预估偏小：在 [quality+1, Q_MAX] 内二分逼近目标大小（最多 3 次），
                                            // 避免直接跳到最高质量把文件顶到容量上限
                                            var lo = quality + 1
                                            var hi = Q_MAX
                                            var iter = 0
                                            while (lo <= hi && iter < 3) {
                                                val mid = (lo + hi) / 2
                                                val bytes = encodeJpeg(scaled, mid)
                                                if (bytes.size <= cap &&
                                                    Math.abs(bytes.size - searchTarget) < Math.abs(compressed.size - searchTarget)) {
                                                    compressed = bytes
                                                }
                                                if (bytes.size < searchTarget) lo = mid + 1 else hi = mid - 1
                                                iter++
                                            }
                                        }
                                        scaled.recycle()

                                        // Preserve original camera EXIF
                                        // 唯一文件名：两线程同时拍照各写各的临时文件，避免互踩（finally 里各自删除）
                                        val tmpFile = java.io.File(GlobalState.appContext.cacheDir, "cam2magic_tmp_${System.nanoTime()}.jpg")
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
                                                Dog.w(TAG, "EXIF pushed JPEG over buffer, dropping EXIF: ${jpeg.size} > $cap", SM.enableLog)
                                                jpeg = compressed
                                            }
                                            jpegCache.put(cacheKey, jpeg)
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
                Dog.e(TAG, "JPEG replacement failed: ${e.message}", e, SM.enableLog)
            }
            if (jpeg == null || jpeg.isEmpty()) {
                Dog.e(TAG, "Failed to get replacement JPEG for format 256", null, SM.enableLog)
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
                    Dog.e(TAG, "Unsafe also failed: ${e2.message}", e2, SM.enableLog)
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
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, SM.enableLog) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireLatestImageHook() {
        val acquireLatestImage = getDeclaredMethod("acquireLatestImage")
        magic.hook(acquireLatestImage).intercept { chain ->
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, SM.enableLog) }
            return@intercept image
        }
    }

    private fun Class<*>.acquireNextImageNoThrowISEHook() {
        val acquireNextImageNoThrowISE = getDeclaredMethod("acquireNextImageNoThrowISE")
        magic.hook(acquireNextImageNoThrowISE).intercept { chain ->
            val image = chain.proceed() as? Image ?: return@intercept null
            runCatching { handleImage(image) }
                .onFailure { Dog.e(TAG, "handleImage failed: ${it.message}", it, SM.enableLog) }
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
