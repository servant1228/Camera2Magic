// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.nothing.camera2magic.ui.component.liquid

// Adapted from Kyant0/AndroidLiquidGlass �?https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** 轻量 vibrancy 效果：提升饱和度，让透出的背景更鲜亮�?*/
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}

