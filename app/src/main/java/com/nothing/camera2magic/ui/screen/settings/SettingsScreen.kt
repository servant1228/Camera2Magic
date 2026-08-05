package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardItem
import com.nothing.camera2magic.ui.component.groupedCardItems
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreenContent(
    viewModel: SettingsViewModel,
    onThemeConfigChanged: (ThemeConfig) -> Unit = {},
    bottomPadding: Dp = 0.dp,
    onNavigateThemeSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.nav_settings),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {},
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            item { SmallTitle(text = stringResource(R.string.settings_general)) }
            groupedCardItems(keyPrefix = "general", items = listOf(
                CardItem("playSound") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_play_sound),
                        summary = stringResource(R.string.quick_entry_play_sound_summary),
                        checked = uiState.playSound,
                        onCheckedChange = { viewModel.onPlaySoundChanged(it) },
                    )
                },
                CardItem("manuallyRotate") {
                    val rotationItems = listOf("0", "90", "180", "270")
                    OverlaySpinnerPreference(
                        items = rotationItems.map { DropdownItem(it) },
                        selectedIndex = uiState.manuallyRotate,
                        title = stringResource(R.string.quick_entry_manually_rotate),
                        summary = stringResource(R.string.quick_entry_manually_rotate_summary),
                        onSelectedIndexChange = { viewModel.onManuallyRotateChanged(it) },
                    )
                },
                CardItem("adaptLandscape") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_adapt_landscape),
                        summary = stringResource(R.string.quick_entry_adapt_landscape_summary),
                        checked = uiState.adaptLandscape,
                        onCheckedChange = { viewModel.onAdaptLandscapeChanged(it) },
                    )
                },
                CardItem("injectMenu") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_inject_menu),
                        summary = stringResource(R.string.quick_entry_inject_menu_summary),
                        checked = uiState.injectMenu,
                        onCheckedChange = { viewModel.onInjectMenuChanged(it) },
                    )
                },
                CardItem("showToast") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_show_toast),
                        summary = stringResource(R.string.quick_entry_show_toast_summary),
                        checked = uiState.showToast,
                        onCheckedChange = { viewModel.onShowToastChanged(it) },
                    )
                },
                CardItem("compressJpeg") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_compress_jpeg),
                        summary = stringResource(R.string.quick_entry_compress_jpeg_summary),
                        checked = uiState.compressJpeg,
                        onCheckedChange = { viewModel.onCompressJpegChanged(it) },
                    )
                },
                CardItem("fixPhotoRotation") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_fix_photo_rotation),
                        summary = stringResource(R.string.quick_entry_fix_photo_rotation_summary),
                        checked = uiState.fixPhotoRotation,
                        onCheckedChange = { viewModel.onFixPhotoRotationChanged(it) },
                    )
                },
                CardItem("enableLog") {
                    SwitchPreference(
                        title = stringResource(R.string.quick_entry_enable_log),
                        summary = stringResource(R.string.quick_entry_enable_log_summary),
                        checked = uiState.enableLog,
                        onCheckedChange = { viewModel.onEnableLogChanged(it) },
                    )
                },
                CardItem("theme") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_appearance_theme),
                        summary = stringResource(R.string.settings_appearance_theme_summary),
                        onClick = onNavigateThemeSettings,
                    )
                },
            ))

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
