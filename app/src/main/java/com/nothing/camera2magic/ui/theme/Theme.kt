package com.nothing.camera2magic.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

val LocalThemeConfig = compositionLocalOf { ThemeConfig() }
val LocalAppDarkMode = staticCompositionLocalOf { false }
val LocalAppMonetEnabled = staticCompositionLocalOf { false }

@Composable
fun Camera2MagicTheme(
    themeConfig: ThemeConfig = LocalThemeConfig.current,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeConfig.resolveIsDark(systemDark)

    val baseLight = remember { miuixLightColorScheme() }
    val baseDark = remember { miuixDarkColorScheme() }

    val colorSchemeMode = when {
        !themeConfig.useMonet && themeConfig.colorMode == 1 -> ColorSchemeMode.Light
        !themeConfig.useMonet && themeConfig.colorMode == 2 -> ColorSchemeMode.Dark
        !themeConfig.useMonet -> ColorSchemeMode.System
        themeConfig.colorMode == 1 -> ColorSchemeMode.MonetLight
        themeConfig.colorMode == 2 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }

    val systemSeedColor = if (themeConfig.useMonet && themeConfig.accentColor == ThemeAccentColor.Default) {
        platformDynamicColors(darkTheme).primary
    } else {
        null
    }
    val keyColor = when {
        !themeConfig.useMonet -> null
        themeConfig.accentColor == ThemeAccentColor.Default -> systemSeedColor
        else -> themeConfig.accentColor.seedColor
    }

    val backgroundLight = if (themeConfig.useMonet) baseLight.background else Color(0xFFF5F5F5)
    val backgroundDark = if (themeConfig.pureBlack) Color(0xFF000000) else baseDark.background
    val surfaceDark = if (themeConfig.pureBlack) Color(0xFF0D0D0D) else baseDark.surface

    val customLight = remember(backgroundLight) { baseLight.copy(background = backgroundLight) }
    val customDark = remember(backgroundDark, surfaceDark) { baseDark.copy(background = backgroundDark, surface = surfaceDark) }

    val controller = remember(themeConfig, colorSchemeMode, keyColor, customLight, customDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            lightColors = customLight,
            darkColors = customDark,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = themeConfig.paletteStyle,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, themeConfig.useMonet, themeConfig.pureBlack, darkTheme) {
        if (themeConfig.useMonet && themeConfig.pureBlack && darkTheme) {
            colors.copy(background = Color.Black, surface = Color.Black)
        } else {
            colors
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MiuixTheme(colors = themedColors) {
        CompositionLocalProvider(
            LocalAppDarkMode provides darkTheme,
            LocalAppMonetEnabled provides themeConfig.useMonet,
            LocalDensity provides Density(LocalDensity.current.density * themeConfig.densityScale, LocalDensity.current.fontScale),
        ) {
            content()
        }
    }
}
