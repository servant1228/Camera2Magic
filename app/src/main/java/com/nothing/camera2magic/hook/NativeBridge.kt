@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.view.Surface
import java.nio.ByteBuffer

/**
 * JNI 契约单点。原生侧在 [JNI_OnLoad] 里用 RegisterNatives 绑定同一张表
 * （见 `app/src/main/cpp/native_bridge.cpp`），**任何签名改动都必须两侧一起**，
 * 否则不是编译错误而是运行期 `UnsatisfiedLinkError`。
 */
object NativeBridge {
    /** 原生日志门控。历史实现无视模块日志开关、常开输出指纹，现在与 `main_enable_log` 同步。 */
    @JvmStatic
    external fun setLogEnabled(enabled: Boolean)

    @JvmStatic
    external fun createOESTexture(): Int
    @JvmStatic
    external fun notifyFrameAvailable()
    @JvmStatic
    external fun setSurfaceTexture(st: SurfaceTexture)
    @JvmStatic
    external fun getSurfaceInfo(surface: Surface): IntArray
    @JvmStatic
    external fun updateCameraBaseData(api: Int, facingFront: Boolean, sensorOri: Int, displayOri: Int, processName: String)

    @JvmStatic
    external fun updateManualRotation(rotation: Int)
    @JvmStatic
    external fun addRenderTarget(surface: Surface, vWidth: Int, vHeight: Int, pWidth: Int, pHeight: Int)
    @JvmStatic
    external fun addRenderTarget(surface: Surface)
    @JvmStatic
    external fun removeRenderTarget(surface: Surface)
    @JvmStatic
    external fun clearTargets()
    @JvmStatic
    external fun updateAlgorithmSize(width: Int, height: Int)
    @JvmStatic
    external fun updateFrameInfo(width: Int, height: Int, rotation: Int)

    /** 把当前替换帧按指定尺寸写成 NV21（Camera1 的 onPreviewFrame 缓冲）。 */
    @JvmStatic
    external fun overwriteYuvBuffer(originBuffer: ByteArray, width: Int, height: Int)

    /** 把当前替换帧写进 Camera2 的 YUV_420_888 三个平面（保留原 stride）。 */
    @JvmStatic
    external fun overwriteYuvBuffer(yBuffer: ByteBuffer, yRowStride: Int, yPixelStride: Int,
                                    uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
                                    vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int)

    /**
     * 把当前替换帧按拍照尺寸编码成 JPEG（含 EXIF Orientation）。
     * 无帧源 / 编码失败时返回 null，调用方应退回原始相机 JPEG。
     */
    @JvmStatic
    external fun overwriteJPEGBytes(quality: Int = 90): ByteArray?
}
