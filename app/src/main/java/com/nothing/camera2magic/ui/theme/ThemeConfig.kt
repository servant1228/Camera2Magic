package com.nothing.camera2magic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.nothing.camera2magic.viewmodel.ConfigRepository
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class ThemeAccentColor(val seedColor: Color) {
    Default(Color(0xFF3482FF)),
    Blue(Color(0xFF3482FF)),
    Purple(Color(0xFF6750A4)),
    Pink(Color(0xFFB0006D)),
    Red(Color(0xFFBA1A1A)),
    Orange(Color(0xFFB65D00)),
    Yellow(Color(0xFF7D5700)),
    Green(Color(0xFF006D3B)),
    Teal(Color(0xFF006A6A)),
}

enum class FloatingBottomBarStyle(val storageValue: String) {
    Miuix("miuix"),
    IosLike("ios_like");

    companion object {
        fun fromStorage(value: String): FloatingBottomBarStyle =
            entries.firstOrNull { it.storageValue == value } ?: Miuix
    }
}

enum class BottomBarMode(val storageValue: String) {
    IconAndText("icon_and_text"),
    IconOnly("icon_only");

    companion object {
        fun fromStorage(value: String): BottomBarMode =
            entries.firstOrNull { it.storageValue == value } ?: IconAndText
    }
}

const val MinDensityScale = 0.8f
const val MaxDensityScale = 1.1f
const val DefaultDensityScale = 1.0f

@Immutable
data class ThemeConfig(
    val colorMode: Int = 0,
    val pureBlack: Boolean = false,
    val useMonet: Boolean = false,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    val accentColor: ThemeAccentColor = ThemeAccentColor.Default,
    val blurEnabled: Boolean = true,
    val predictiveBack: Boolean = false,
    val floatingBottomBar: Boolean = false,
    val floatingBottomBarStyle: FloatingBottomBarStyle = FloatingBottomBarStyle.Miuix,
    val bottomBarMode: BottomBarMode = BottomBarMode.IconAndText,
    val densityScale: Float = DefaultDensityScale,
)

fun ThemeConfig.resolveIsDark(systemDark: Boolean): Boolean = when (colorMode) {
    1 -> false
    2 -> true
    else -> systemDark
}

fun normalizeDensityScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinDensityScale, MaxDensityScale) else DefaultDensityScale

fun themePaletteStyleFromStorage(value: String): ThemePaletteStyle = when (value) {
    "Spritz" -> ThemePaletteStyle.Vibrant
    else -> runCatching {
        ThemePaletteStyle.valueOf(value)
    }.getOrDefault(ThemePaletteStyle.TonalSpot)
}

fun readThemeConfig(repository: ConfigRepository): ThemeConfig {
    return ThemeConfig(
        colorMode = repository.themeDarkMode,
        pureBlack = repository.themePureBlack,
        useMonet = repository.themeMonet,
        paletteStyle = themePaletteStyleFromStorage(repository.themePaletteStyle),
        accentColor = runCatching {
            ThemeAccentColor.entries.find { it.name == repository.themeAccentColor }
                ?: ThemeAccentColor.Default
        }.getOrDefault(ThemeAccentColor.Default),
        blurEnabled = repository.themeBlurEnabled,
        floatingBottomBar = repository.themeFloatingBottomBar,
        floatingBottomBarStyle = FloatingBottomBarStyle.fromStorage(repository.themeFloatingBottomBarStyle),
        bottomBarMode = BottomBarMode.fromStorage(repository.themeBottomBarMode),
        densityScale = normalizeDensityScale(repository.themeDensityScale),
        predictiveBack = repository.themePredictiveBack,
    )
}

fun writeThemeConfig(repository: ConfigRepository, config: ThemeConfig) {
    repository.themeDarkMode = config.colorMode
    repository.themePureBlack = config.pureBlack
    repository.themeMonet = config.useMonet
    repository.themePaletteStyle = config.paletteStyle.name
    repository.themeAccentColor = config.accentColor.name
    repository.themeBlurEnabled = config.blurEnabled
    repository.themeFloatingBottomBar = config.floatingBottomBar
    repository.themeFloatingBottomBarStyle = config.floatingBottomBarStyle.storageValue
    repository.themeBottomBarMode = config.bottomBarMode.storageValue
    repository.themeDensityScale = normalizeDensityScale(config.densityScale)
    repository.themePredictiveBack = config.predictiveBack
}
