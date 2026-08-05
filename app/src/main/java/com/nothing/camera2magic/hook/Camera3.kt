package com.nothing.camera2magic.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture

import android.media.ExifInterface
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.annotation.OptIn

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog

import java.io.FileInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM

class Camera3 {

    companion object {
        private const val TAG = "[Camera3]"

        private val camera3Handler = Camera3Extended.handler

        private val context: Context get() = GlobalState.appContext

        @Volatile
        private var initialized = AtomicBoolean(false)
        private var player: ExoPlayer? = null
        private var pfd: ParcelFileDescriptor? = null
        private var imageRendering: Boolean = false
        private var cachedBitmap: Bitmap? = null
        private var oesTextureId: Int = 0
        private var surface: Surface? = null
        private var surfaceTexture: SurfaceTexture? = null
        @Volatile
        private var lastFrameW = 0
        @Volatile
        private var lastFrameH = 0
        @Volatile
        private var lastNaturalW = 0
        @Volatile
        private var lastNaturalH = 0
        @Volatile
        private var lastRotation = 0

        /** 拍照时按需切换原生帧信息：预览用适配值，JPEG 生成用自然值。 */
        fun applyFrameInfoToNative(useNatural: Boolean) {
            val w = if (useNatural) lastNaturalW else lastFrameW
            val h = if (useNatural) lastNaturalH else lastFrameH
            if (w > 0 && h > 0) NB.updateFrameInfo(w, h, lastRotation)
        }
    }

    enum class State { IDLE, BUFFERING, READY, ENDED, PLAYING, PAUSE, ERROR }
    var onPlayerStateChangeListener: ((state: State) -> Unit)? = null

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val pixelRatio = videoSize.pixelWidthHeightRatio
            val width = (videoSize.width * pixelRatio).toInt()
            val height = videoSize.height
            val rotation = videoSize.unappliedRotationDegrees
            // 横屏适配：原生引擎把“帧宽高比”和“反向目标宽高比”比较来算缩放，
            // 横屏对横屏时会产生数倍横向缩放导致画面被压扁；上报交换后的宽高可让缩放归 1
            val (frameW, frameH) = if (SM.adaptLandscape) height to width else width to height
            lastNaturalW = width
            lastNaturalH = height
            lastRotation = rotation
            lastFrameW = frameW
            lastFrameH = frameH
            NB.updateFrameInfo(frameW, frameH, rotation)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when(playbackState) {
                Player.STATE_IDLE -> notifyState(State.IDLE)
                Player.STATE_BUFFERING -> notifyState(State.BUFFERING)
                Player.STATE_READY -> notifyState(State.READY)
                Player.STATE_ENDED -> notifyState(State.ENDED)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) notifyState(State.PLAYING)
            else if (player?.playbackState != Player.STATE_ENDED) notifyState(State.PAUSE)
        }

        override fun onPlayerError(error: PlaybackException) {
            Dog.e(TAG, "${error.errorCodeName} - ${error.message}", error, true)
            notifyState(State.ERROR)
        }
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        oesTextureId = NB.createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(16, 16)
            setOnFrameAvailableListener({ _ ->
                NB.notifyFrameAvailable()
            }, camera3Handler)
        }

        NB.setSurfaceTexture(surfaceTexture!!)
        surface = Surface(surfaceTexture)

        player = ExoPlayer.Builder(GlobalState.appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(playerListener)
        }
        Dog.i(TAG, "camera3 client initialized.", SM.enableLog)
    }

    fun start(magic: MagicHook, validMedia: ValidMedia) {
        camera3Handler.post {
            init()
            val (name, type) = validMedia
            when (type) {
                MagicType.NETWORK_RTSP -> handleNetworkRtsp(name)
                MagicType.LOCAL_VIDEO  -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalVideo(it) }
                }

                MagicType.LOCAL_IMAGE -> {
                    pfd = magic.openRemoteFile(name)
                    pfd?.let { handleLocalImage(it) }
                }
            }
        }
    }

    private fun handleNetworkRtsp(rtspUrl: String) {
        val volumeValue = if (SM.playSound) 1f else 0f
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaItem(MediaItem.fromUri(rtspUrl))
                prepare()
                playWhenReady = true
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun handleLocalVideo(pfd: ParcelFileDescriptor) {
        val volumeValue = if (SM.playSound) 1f else 0f
        val factory = DataSource.Factory { MagicDataSource(pfd) }
        val mediaSourceFactory = DefaultMediaSourceFactory(factory)
        val mediaItem = MediaItem.fromUri("LOCAL://VIDEO")
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                prepare()
                playWhenReady = true
            }
        }
    }

    private fun handleLocalImage(pfd: ParcelFileDescriptor) {

        runCatching {
            val fd = pfd.fileDescriptor

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFileDescriptor(fd, null, options)

            try {
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
            } catch (e: Exception) {
                Dog.e(TAG, "Failed to seek file descriptor", e, SM.enableLog)
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options)


            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                ?: throw IllegalStateException("decode image failed.")

            // 横屏适配：图片自身 EXIF 方向先烘焙进像素，避免横屏图片在预览里被旋转
            val oriented = if (SM.adaptLandscape) {
                val orientation = runCatching {
                    Os.lseek(fd, 0, OsConstants.SEEK_SET)
                    ExifInterface(FileInputStream(fd))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                rotateBitmap(bitmap, orientation)
            } else bitmap

            // 与视频路径一致：横屏适配时交换帧宽高，避免原生缩放把画面压扁
            val (frameW, frameH) = if (SM.adaptLandscape) oriented.height to oriented.width else oriented.width to oriented.height
            lastNaturalW = oriented.width
            lastNaturalH = oriented.height
            lastRotation = 0
            lastFrameW = frameW
            lastFrameH = frameH
            NB.updateFrameInfo(frameW, frameH, 0)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(oriented.width, oriented.height)
            cachedBitmap = oriented
            imageRendering = true
            camera3Handler.post(imageRenderRunnable)
        }.onFailure { e ->
            Dog.e(TAG, "${e.message}", e, true)
        }
    }

    private val imageRenderRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get() || !imageRendering) return
            drawBitmapToSurface()
            if (imageRendering) camera3Handler.postDelayed(this, 33L)
        }
    }

    private fun drawBitmapToSurface() {
        val bitmap = cachedBitmap ?: return
        runCatching {
            val canvas = surface?.lockHardwareCanvas()// minSDK 26
            canvas?.let {
                it.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                it.drawBitmap(bitmap, 0f, 0f, null)
            }
            surface?.unlockCanvasAndPost(canvas)
        }
    }
    fun pause () {
        camera3Handler.post { player?.playWhenReady = false }
    }
    fun seekTo(position: Long) { // Ms
        camera3Handler.post { player?.seekTo(position) }
    }
    fun stop() {
        if (!initialized.get()) return
        camera3Handler.post {
            imageRendering = false
            camera3Handler.removeCallbacks(imageRenderRunnable)
            player?.release()
            releaseResources()
            initialized.set(false)
        }
    }

    fun releaseResources() {
        if (cachedBitmap != null) {
            val tmp = cachedBitmap
            cachedBitmap = null
            tmp?.recycle()
        }
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        oesTextureId = 0
        pfd?.close()
        pfd = null
    }

    private fun notifyState(state: State) {
        onPlayerStateChangeListener?.invoke(state)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int = 1080, reqHeight: Int = 1920): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** 按图片自身 EXIF 方向旋转到显示方向（正值 = 顺时针）。 */
    private fun rotateBitmap(src: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> -90f   // 6
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f  // 3
            ExifInterface.ORIENTATION_ROTATE_270 -> 90f   // 8
            else -> 0f
        }
        if (degrees == 0f) return src
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
