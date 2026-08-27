package com.nothing.camera2magic.ui.screen.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.ui.graphics.vector.ImageVector
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.theme.RunState

/**
 * 主页状态卡两态：
 * - [Inactive]：LSPosed 未加载本模块（未收到 XposedService binder）；
 * - [Enabled]：LSPosed 已激活。
 *
 * 展示元数据（icon/runState/titleRes）集中于此，UI 侧不得再按状态重复分支映射。
 */
enum class ModuleStatus(
    val icon: ImageVector,
    val runState: RunState,
    @StringRes val titleRes: Int,
) {
    Enabled(
        icon = Icons.Rounded.CheckCircleOutline,
        runState = RunState.Running,
        titleRes = R.string.home_status_enabled,
    ),
    Inactive(
        icon = Icons.Rounded.RemoveCircleOutline,
        runState = RunState.Stopped,
        titleRes = R.string.home_status_inactive,
    ),
}

fun moduleStatus(xposedActive: Boolean): ModuleStatus = when {
    !xposedActive -> ModuleStatus.Inactive
    else -> ModuleStatus.Enabled
}
