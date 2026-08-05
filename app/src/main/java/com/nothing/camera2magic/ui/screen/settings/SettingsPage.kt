package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.viewmodel.SettingsViewModel

@Composable
fun SettingsPage(
    bottomPadding: Dp = 0.dp,
    onThemeConfigChanged: (ThemeConfig) -> Unit = {},
    onNavigateThemeSettings: () -> Unit = {},
) {
    val factory = com.nothing.camera2magic.viewmodel.LocalViewModelFactory.current
    val settingsViewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)

    SettingsScreenContent(
        viewModel = settingsViewModel,
        onThemeConfigChanged = onThemeConfigChanged,
        bottomPadding = bottomPadding,
        onNavigateThemeSettings = onNavigateThemeSettings,
    )
}
