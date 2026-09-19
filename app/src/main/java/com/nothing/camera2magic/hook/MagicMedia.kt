package com.nothing.camera2magic.hook

import android.net.Uri

enum class MagicType(val value: Int, val label: String) {
    LOCAL_VIDEO(0x0000, "video"),
    LOCAL_IMAGE(0x0001, "image"),
    // 网络视频流：`ValidMedia.file` 里放的是 URL（rtsp/http/hls），不走 openRemoteFile、不落盘
    NETWORK_STREAM(0x0100, "stream");
    companion object {
        fun fromValue(value: Int): MagicType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid MediaSource value: $value")
        }
    }
}

data class ValidMedia (
    var file: String,
    var type: MagicType
)
