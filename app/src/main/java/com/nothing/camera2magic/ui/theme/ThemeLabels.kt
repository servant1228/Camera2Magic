package com.nothing.camera2magic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nothing.camera2magic.R
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun ThemePaletteStyle.label(): String = stringResource(
    when (this) {
        ThemePaletteStyle.TonalSpot -> R.string.settings_theme_palette_tonal_spot
        ThemePaletteStyle.Neutral -> R.string.settings_theme_palette_neutral
        ThemePaletteStyle.Vibrant -> R.string.settings_theme_palette_vibrant
        ThemePaletteStyle.Expressive -> R.string.settings_theme_palette_expressive
        ThemePaletteStyle.Rainbow -> R.string.settings_theme_palette_rainbow
        ThemePaletteStyle.FruitSalad -> R.string.settings_theme_palette_fruit_salad
        ThemePaletteStyle.Monochrome -> R.string.settings_theme_palette_monochrome
        ThemePaletteStyle.Fidelity -> R.string.settings_theme_palette_fidelity
        ThemePaletteStyle.Content -> R.string.settings_theme_palette_content
    },
)

@Composable
fun ThemeAccentColor.label(): String = stringResource(
    when (this) {
        ThemeAccentColor.Default -> R.string.settings_theme_accent_default
        ThemeAccentColor.Blue -> R.string.settings_theme_accent_blue
        ThemeAccentColor.Purple -> R.string.settings_theme_accent_purple
        ThemeAccentColor.Pink -> R.string.settings_theme_accent_pink
        ThemeAccentColor.Red -> R.string.settings_theme_accent_red
        ThemeAccentColor.Orange -> R.string.settings_theme_accent_orange
        ThemeAccentColor.Yellow -> R.string.settings_theme_accent_yellow
        ThemeAccentColor.Green -> R.string.settings_theme_accent_green
        ThemeAccentColor.Teal -> R.string.settings_theme_accent_teal
    },
)
