package com.nothing.camera2magic.ui.util

import android.os.Build
import java.util.Locale

/**
 * 机型显示名：优先读厂商市场名 system prop（如一加 PLR110 → 一加 Ace 6T），
 * 读不到时回退 Build.MODEL。市场名与型号码不同时附带型号码，便于排查问题。
 */
object DeviceName {

    // 各厂商市场名 prop，按优先级排列；zh 环境优先中文市场名，其余优先英文名
    private val zhCandidates = listOf(
        "ro.vendor.oplus.market.name",
        "ro.oplus.market.name",
        "ro.product.marketname",
        "ro.vendor.product.marketname",
    )
    private val enCandidates = listOf(
        "ro.vendor.oplus.market.enname",
        "ro.product.marketname",
        "ro.vendor.product.marketname",
    )

    private fun sysprop(key: String): String? = runCatching {
        @Suppress("PrivateApi")
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }

    fun display(): String {
        val candidates = if (Locale.getDefault().language == "zh") zhCandidates else enCandidates
        // 市场名优先；读不到或与型号码相同才回退 Build.MODEL
        return candidates.firstNotNullOfOrNull(::sysprop)
            ?.takeIf { !it.equals(Build.MODEL, true) }
            ?: Build.MODEL
    }
}
