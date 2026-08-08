package com.nothing.camera2magic.ui.screen.home

/**
 * 主页状态卡三态：
 * - [Inactive]：LSPosed 未加载本模块（未收到 XposedService binder）；
 * - [Disabled]：LSPosed 已激活，但 App 内“模块总开关”关闭；
 * - [Enabled]：两者都开启。
 */
enum class ModuleStatus {
    Enabled,
    Disabled,
    Inactive,
}

fun moduleStatus(xposedActive: Boolean, masterSwitchEnabled: Boolean): ModuleStatus = when {
    !xposedActive -> ModuleStatus.Inactive
    !masterSwitchEnabled -> ModuleStatus.Disabled
    else -> ModuleStatus.Enabled
}
