package com.nothing.camera2magic.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius

/**
 * 卡片圆角同心跟随系统屏幕圆角：内层半径 = 屏幕半径 − 与屏幕边缘的间距，
 * 直屏（屏幕半径为 0）回落到 miuix 默认卡片圆角。
 */
@Composable
fun rememberConcentricCardRadius(inset: Dp = 12.dp): Dp =
    (rememberNavSystemCornerRadius() - inset).coerceAtLeast(CardDefaults.CornerRadius)
