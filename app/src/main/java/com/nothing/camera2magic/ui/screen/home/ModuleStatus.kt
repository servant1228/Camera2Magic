package com.nothing.camera2magic.ui.screen.home

/**
 * 主页状态卡两态：
 * - [Inactive]：LSPosed 未加载本模块（未收到 XposedService binder）；
 * - [Enabled]：LSPosed 已激活。
 */
enum class ModuleStatus {
    Enabled,
    Inactive,
}

fun moduleStatus(xposedActive: Boolean): ModuleStatus = when {
    !xposedActive -> ModuleStatus.Inactive
    else -> ModuleStatus.Enabled
}
