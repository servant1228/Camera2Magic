package com.nothing.camera2magic.utils

import android.util.Log

/**
 * 全局日志器：统一写入 logcat（tag=VCX），查看方式为 `adb logcat -s VCX:*`。
 * enabled 参数由调用方传入（通常为 SM.enableLog / Dog.enabled）。
 */
object Dog {
    private const val TAG = "VCX"

    var enabled: Boolean = false

    fun i(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        Log.i(TAG, "[${tag ?: ""}] $message")
    }

    fun w(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        Log.w(TAG, "[${tag ?: ""}] $message")
    }

    fun e(tag: String? = null, message: String, throwable: Throwable? = null, enabled: Boolean = this.enabled) {
        if (!enabled) return
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        Log.e(TAG, "[${tag ?: ""}] $msg", throwable)
    }
}
