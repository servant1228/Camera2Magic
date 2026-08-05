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
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

val LocalThemeConfig = compositionLocalOf { ThemeConfig() }
val LocalAppDarkMode = staticCompositionLocalOf { false }

@Composable
fun Camera2MagicTheme(
    themeConfig: ThemeConfig = LocalThemeConfig.current,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeConfig.resolveIsDark(systemDark)

    val mode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light

    val baseLight = remember { miuixLightColorScheme() }
    val baseDark = remember { miuixDarkColorScheme() }

    val backgroundLight = if (themeConfig.useMonet) baseLight.background else Color(0xFFF5F5F5)
    val backgroundDark = if (themeConfig.pureBlack) Color(0xFF000000) else baseDark.background
    val surfaceDark = if (themeConfig.pureBlack) Color(0xFF0D0D0D) else baseDark.surface

    val customLight = remember(backgroundLight) { baseLight.copy(background = backgroundLight) }
    val customDark = remember(backgroundDark, surfaceDark) { baseDark.copy(background = backgroundDark, surface = surfaceDark) }

    val controller = remember(darkTheme, customLight, customDark) {
        ThemeController(mode, lightColors = customLight, darkColors = customDark)
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

    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalAppDarkMode provides darkTheme,
            LocalDensity provides Density(LocalDensity.current.density * themeConfig.densityScale, LocalDensity.current.fontScale),
        ) {
            content()
        }
    }
}
