package com.nothing.camera2magic.hook

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture

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

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.hook.SourceManager as SM

class Camera3 {

    companion object {
        private const val TAG = "[Camera3]"

        // 图片媒体解码预算：长边上限（横竖对称）。4K 是下游有用分辨率的天花板
        // （native 缩放到输出端，输出最大 4K 录制），3840 同时保证低于最低保障的
        // GL_MAX_TEXTURE_SIZE(4096)，避免 lockHardwareCanvas 超限静默失败变黑帧
        private const val FRAME_LONG_EDGE = 3840
        private const val FRAME_LONG_EDGE_LOW_RAM = 1920

        // 帧重绘间隔 ≈30fps：仅驱动 OES 纹理持续更新，实际输出帧率由相机管线决定
        private const val FRAME_INTERVAL_MS = 33L

        // 网络流断线重试节奏：直播流断线很常见，而 ExoPlayer 默认不自动重连
        private const val NETWORK_RETRY_DELAY_MS = 2000L
        private const val MAX_NETWORK_RETRIES = 3

        // Hook 跑在目标应用进程，内存账算它的：低内存设备降档
        private val frameLongEdge: Int by lazy {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.isLowRamDevice == true
            }.getOrDefault(false).let { if (it) FRAME_LONG_EDGE_LOW_RAM else FRAME_LONG_EDGE }
        }

        private val camera3Handler = Camera3Extended.handler

        private val context: Context get() = GlobalState.appContext

        @Volatile
        private var initialized = AtomicBoolean(false)
        private var player: ExoPlayer? = null
        private var pfd: ParcelFileDescriptor? = null
        @Volatile
        private var activeType: MagicType? = null
        // 当前正在播的网络流 URL：断流重试时靠它判断「这条流还是不是当前生效的媒体」
        @Volatile
        private var activeNetworkUrl: String? = null
        // 网络流重试计数。onPlayerError/onIsPlayingChanged 在目标应用主线程回调，
        // 而重试体在 camera3Handler 线程，必须 @Volatile（丢失一次归零只会多重试一轮，不致命）
        @Volatile
        private var networkRetryCount = 0
        private var imageRendering: Boolean = false
        private var cachedBitmap: Bitmap? = null
        private var oesTextureId: Int = 0
        private var surface: Surface? = null
        private var surfaceTexture: SurfaceTexture? = null
    }

    enum class State { IDLE, BUFFERING, READY, ENDED, PLAYING, PAUSE, ERROR }
    var onPlayerStateChangeListener: ((state: State) -> Unit)? = null

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val pixelRatio = videoSize.pixelWidthHeightRatio
            val width = (videoSize.width * pixelRatio).toInt()
            val height = videoSize.height
            val rotation = videoSize.unappliedRotationDegrees
            Dog.i(TAG, "video size: ${videoSize.width}x${videoSize.height} ratio=$pixelRatio -> ${width}x$height rot=$rotation", SM.enableLog)
            NB.updateFrameInfo(width, height, rotation)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(width, height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Dog.i(TAG, "player state: ${stateName(playbackState)}", SM.enableLog)
            when(playbackState) {
                Player.STATE_IDLE -> notifyState(State.IDLE)
                Player.STATE_BUFFERING -> notifyState(State.BUFFERING)
                Player.STATE_READY -> notifyState(State.READY)
                Player.STATE_ENDED -> notifyState(State.ENDED)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // 播放恢复正常，重试计数归零
                networkRetryCount = 0
                notifyState(State.PLAYING)
            } else if (player?.playbackState != Player.STATE_ENDED) notifyState(State.PAUSE)
        }

        override fun onPlayerError(error: PlaybackException) {
            Dog.e(TAG, "${error.errorCodeName} - ${error.message} | cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}", error, SM.enableLog)
            notifyState(State.ERROR)
            scheduleNetworkRetry()
        }
    }

    private fun stateName(s: Int): String = when (s) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($s)"
    }

    @OptIn(UnstableApi::class)
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        // 这个函数跑在 "Camera3" 的 HandlerThread 上，抛到 Looper 就是目标应用直接挂掉；
        // NB.createOESTexture() 是 native 调用、ExoPlayer.Builder.build() 也会抛，都不能裸着。
        // 失败后复位 initialized 让下一次 start 能重试（宁可留一份部分资源，也不要从此不再重建管线）
        runCatching {
            oesTextureId = NB.createOESTexture()
            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(16, 16)
                setOnFrameAvailableListener({ _ ->
                    NB.notifyFrameAvailable()
                }, camera3Handler)
            }

            NB.setSurfaceTexture(surfaceTexture!!)
            surface = Surface(surfaceTexture)

            // 起播门槛从默认 1000ms 降到 300ms：网络流在弱网/高延迟下要等够 1s 媒体
            // 才出第一帧，用户会当成黑屏。注意本地视频的 uri 是 "LOCAL://VIDEO"，
            // 不在 DefaultLoadControl 的 LOCAL_PLAYBACK_SCHEMES 里，同样按 streaming 配置走
            player = ExoPlayer.Builder(GlobalState.appContext)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming(1500, 15000, 300, 800)
                        .build()
                )
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    addListener(playerListener)
                }
            Dog.i(TAG, "camera3 client initialized.", SM.enableLog)
        }.onFailure { e ->
            initialized.set(false)
            Dog.e(TAG, "camera3 client init failed: ${e.message}", e, SM.enableLog)
        }
    }

    fun start(magic: MagicHook, validMedia: ValidMedia) {
        camera3Handler.post {
            val (name, type) = validMedia
            if (initialized.get() && activeType != null && activeType != type) {
                // 活管线上的媒体类型变了（前置图 / 后置视频这种搭配）：按「先拆再起」重建整条管线。
                // ExoPlayer 与 lockHardwareCanvas 两种生产者不能混挂在同一个 Surface 上；
                // 同类型切换（换一张图 / 换一个视频）仍走原地换源，不拆管线。
                stop()
                camera3Handler.post { openMedia(magic, name, type) }
                return@post
            }
            openMedia(magic, name, type)
        }
    }

    private fun openMedia(magic: MagicHook, name: String, type: MagicType) {
        init()
        // 旧 fd 先关再赋新值：连续两次 start 之间没有 stop 时旧 fd 会泄漏。
        // DataSource 在 open 时已 dup 私有副本，关它不影响仍在读的旧播放
        runCatching { pfd?.close() }
        pfd = null
        // 每次换源先清掉上一条网络流的重试状态；network 分支会重新赋值
        activeNetworkUrl = null
        networkRetryCount = 0
        // openRemoteFile 会抛 FileNotFoundException，而这个 block 跑在 Camera3 的 HandlerThread 上，
        // 漏到 Looper 就是目标应用直接挂掉（媒体文件被删 / 模块数据被清时就会遇到）
        when (type) {
            MagicType.NETWORK_STREAM -> {
                if (name.isNotBlank()) {
                    handleNetworkStream(name)
                    // 同本地媒体的「拿到帧源才记类型」：init() 失败时 player 不可用，
                    // 记了类型会让下一次同类型重起跳过重建
                    if (initialized.get()) activeType = type
                }
            }

            MagicType.LOCAL_VIDEO -> {
                pfd = runCatching { magic.openRemoteFile(name) }
                    .onFailure { Dog.e(TAG, "open remote video failed: ${it.message}", it, SM.enableLog) }
                    .getOrNull()
                pfd?.let { handleLocalVideo(it) }
                // 只在真的拿到帧源时记类型；失败时保持原值，下次同类型重起仍是原地换源
                if (pfd != null) activeType = type
            }

            MagicType.LOCAL_IMAGE -> {
                pfd = runCatching { magic.openRemoteFile(name) }
                    .onFailure { Dog.e(TAG, "open remote image failed: ${it.message}", it, SM.enableLog) }
                    .getOrNull()
                pfd?.let { handleLocalImage(it) }
                if (pfd != null) activeType = type
            }
        }
    }

    /**
     * 网络视频流：直接用 ExoPlayer 默认的 HTTP/RTSP 工厂拉流，不经过 [MagicDataSource]。
     * 注意这里没有 pfd，[openMedia] 里的 activeType 由本分支自己记。
     */
    private fun handleNetworkStream(url: String) {
        activeNetworkUrl = url
        val volumeValue = if (SM.playSound) 1f else 0f
        Dog.i(TAG, "open network stream: $url (volume=$volumeValue)", SM.enableLog)
        camera3Handler.post {
            player?.apply {
                volume = volumeValue
                setVideoSurface(surface)
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            } ?: Dog.e(TAG, "network stream skipped: player is null", null, SM.enableLog)
        }
    }

    /**
     * 网络流断线重试。只在「这条流仍是当前生效媒体」且管线还活着时重试，
     * 否则（用户改了配置 / 清了媒体 / 换了镜头）静默放弃。
     */
    private fun scheduleNetworkRetry() {
        val url = activeNetworkUrl ?: return
        if (!initialized.get()) return
        if (networkRetryCount >= MAX_NETWORK_RETRIES) {
            Dog.w(TAG, "network stream retry exhausted: $url", SM.enableLog)
            return
        }
        networkRetryCount++
        Dog.w(TAG, "network stream error, retry #$networkRetryCount in ${NETWORK_RETRY_DELAY_MS}ms: $url", SM.enableLog)
        camera3Handler.removeCallbacks(networkRetryRunnable)
        camera3Handler.postDelayed(networkRetryRunnable, NETWORK_RETRY_DELAY_MS)
    }

    private val networkRetryRunnable = Runnable {
        val url = activeNetworkUrl ?: return@Runnable
        if (!initialized.get()) return@Runnable
        // 期间媒体被切走 / 清空：放弃重试
        if (SM.validMedia?.type != MagicType.NETWORK_STREAM || SM.validMedia?.file != url) return@Runnable
        runCatching {
            player?.apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }.onFailure { Dog.e(TAG, "network stream retry failed: ${it.message}", it, SM.enableLog) }
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
            options.inSampleSize = calculateInSampleSize(options, frameLongEdge)


            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                ?: throw IllegalStateException("decode image failed.")

            NB.updateFrameInfo(bitmap.width, bitmap.height, 0)
            SM.applyManualRotationToNative()
            surfaceTexture?.setDefaultBufferSize(bitmap.width, bitmap.height)
            cachedBitmap = bitmap
            imageRendering = true
            camera3Handler.post(imageRenderRunnable)
        }.onFailure { e ->
            Dog.e(TAG, "${e.message}", e, SM.enableLog)
        }
    }

    private val imageRenderRunnable = object : Runnable {
        override fun run() {
            if (!initialized.get() || !imageRendering) return
            drawBitmapToSurface()
            if (imageRendering) camera3Handler.postDelayed(this, FRAME_INTERVAL_MS)
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
            camera3Handler.removeCallbacks(networkRetryRunnable)
            player?.release()
            releaseResources()
            activeType = null
            activeNetworkUrl = null
            networkRetryCount = 0
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxLongEdge: Int): Int {
        // 旧实现按 reqWidth/reqHeight 双边收紧且循环条件要求两个半边都 >= 预算，
        // 只有竖图真正受限：横图（高 < 2×reqHeight）一律 inSampleSize=1 全尺寸解码
        // （4000×3000 = 48MB）。改为按长边对称收紧，语义「不超过」：pow2 粒度最坏
        // 落到预算一半，但绝不超限、绝不放大小图
        val longEdge = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longEdge / inSampleSize > maxLongEdge) inSampleSize *= 2
        return inSampleSize
    }

}
