package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardItem
import com.nothing.camera2magic.ui.component.groupedCardItems
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.theme.BottomBarMode
import com.nothing.camera2magic.ui.theme.FloatingBottomBarStyle
import com.nothing.camera2magic.ui.theme.MinDensityScale
import com.nothing.camera2magic.ui.theme.MaxDensityScale
import com.nothing.camera2magic.ui.theme.ThemeAccentColor
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.ui.theme.label
import com.nothing.camera2magic.ui.theme.normalizeDensityScale
import com.nothing.camera2magic.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import com.nothing.camera2magic.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ThemeSettingsScreen(
    viewModel: SettingsViewModel,
    onThemeConfigChanged: (ThemeConfig) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeConfig = uiState.themeConfig
    val scrollBehavior = MiuixScrollBehavior()
    var densityScaleDraft by remember { mutableFloatStateOf((themeConfig.densityScale * 100f).roundToInt().toFloat()) }
    var showDensityScaleDialog by remember { mutableStateOf(false) }
    val densityScaleTextState = rememberTextFieldState()

    LaunchedEffect(themeConfig.densityScale) {
        densityScaleDraft = (themeConfig.densityScale * 100f).roundToInt().toFloat()
    }

    fun updateTheme(next: ThemeConfig) {
        viewModel.onThemeConfigChanged(next)
        onThemeConfigChanged(next)
    }

    fun updateDensityScale(percent: Float) {
        val nextPercent = percent.roundToInt().coerceIn(
            (MinDensityScale * 100f).roundToInt(),
            (MaxDensityScale * 100f).roundToInt(),
        )
        val nextScale = normalizeDensityScale(nextPercent / 100f)
        densityScaleDraft = nextScale * 100f
        updateTheme(themeConfig.copy(densityScale = nextScale))
    }

    fun openDensityScaleDialog() {
        densityScaleTextState.setTextAndPlaceCursorAtEnd(densityScaleDraft.roundToInt().toString())
        showDensityScaleDialog = true
    }

    val themeItems = listOf(
        stringResource(R.string.settings_theme_system),
        stringResource(R.string.settings_theme_light),
        stringResource(R.string.settings_theme_dark),
    )
    val paletteStyles = ThemePaletteStyle.entries.toList()
    val paletteItems = paletteStyles.map { it.label() }
    val selectedPaletteIndex = paletteStyles.indexOf(themeConfig.paletteStyle).coerceAtLeast(0)
    val accentOptions = ThemeAccentColor.entries.toList()
    val accentItems = accentOptions.map { it.label() }
    val selectedAccentIndex = accentOptions.indexOf(themeConfig.accentColor).coerceAtLeast(0)
    val floatingBottomBarStyles = FloatingBottomBarStyle.entries.toList()
    val floatingBottomBarStyleItems = floatingBottomBarStyles.map { it.label() }
    val selectedFloatingBottomBarStyleIndex = floatingBottomBarStyles.indexOf(themeConfig.floatingBottomBarStyle).coerceAtLeast(0)
    val bottomBarModes = BottomBarMode.entries.toList()
    val bottomBarModeItems = bottomBarModes.map { it.label() }
    val selectedBottomBarModeIndex = bottomBarModes.indexOf(themeConfig.bottomBarMode).coerceAtLeast(0)
    val isBlurSupported = isRuntimeShaderSupported()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_appearance_theme),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            item { SmallTitle(text = stringResource(R.string.settings_color_theme)) }
            groupedCardItems(
                keyPrefix = "theme_color",
                items = listOf(
                    CardItem("mode") {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_theme_mode),
                            summary = themeItems.getOrElse(themeConfig.colorMode) { themeItems.first() },
                            items = themeItems,
                            selectedIndex = themeConfig.colorMode,
                            onSelectedIndexChange = { index ->
                                updateTheme(themeConfig.copy(colorMode = index))
                            },
                        )
                    },
                    CardItem("monet") {
                        SwitchPreference(
                            title = stringResource(R.string.settings_monet),
                            summary = stringResource(R.string.settings_monet_summary),
                            checked = themeConfig.useMonet,
                            onCheckedChange = { checked ->
                                updateTheme(themeConfig.copy(useMonet = checked))
                            },
                        )
                        AnimatedVisibility(
                            visible = themeConfig.useMonet,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            Column {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.settings_palette_style),
                                    summary = paletteItems.getOrElse(selectedPaletteIndex) { paletteItems.first() },
                                    items = paletteItems,
                                    selectedIndex = selectedPaletteIndex,
                                    onSelectedIndexChange = { index ->
                                        updateTheme(themeConfig.copy(paletteStyle = paletteStyles[index]))
                                    },
                                )
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.settings_accent_color),
                                    summary = accentItems.getOrElse(selectedAccentIndex) { accentItems.first() },
                                    items = accentItems,
                                    selectedIndex = selectedAccentIndex,
                                    onSelectedIndexChange = { index ->
                                        updateTheme(themeConfig.copy(accentColor = accentOptions[index]))
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.settings_pure_black),
                                    summary = stringResource(R.string.settings_pure_black_summary),
                                    checked = themeConfig.pureBlack,
                                    onCheckedChange = { checked ->
                                        updateTheme(themeConfig.copy(pureBlack = checked))
                                    },
                                )
                            }
                        }
                    },
                ),
            )

            item { SmallTitle(text = stringResource(R.string.settings_interface)) }
            groupedCardItems(
                keyPrefix = "theme_interface",
                items = buildList {
                    add(CardItem("blur") {
                        SwitchPreference(
                            title = stringResource(R.string.settings_blur),
                            summary = stringResource(R.string.settings_blur_summary),
                            checked = themeConfig.blurEnabled && isBlurSupported,
                            onCheckedChange = { checked ->
                                updateTheme(themeConfig.copy(blurEnabled = checked))
                            },
                            enabled = isBlurSupported,
                        )
                    })
                    add(CardItem("predictiveBack") {
                        SwitchPreference(
                            title = stringResource(R.string.settings_predictive_back),
                            summary = stringResource(R.string.settings_predictive_back_summary),
                            checked = themeConfig.predictiveBack,
                            onCheckedChange = { checked ->
                                updateTheme(themeConfig.copy(predictiveBack = checked))
                            },
                        )
                    })
                    add(CardItem("densityScale") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_density_scale),
                            summary = stringResource(R.string.settings_density_scale_summary),
                            endActions = {
                                Text(
                                    text = "${densityScaleDraft.roundToInt()}%",
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            bottomAction = {
                                Slider(
                                    value = densityScaleDraft.coerceIn(
                                        MinDensityScale * 100f,
                                        MaxDensityScale * 100f,
                                    ),
                                    onValueChange = { value ->
                                        densityScaleDraft = value
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    valueRange = (MinDensityScale * 100f)..(MaxDensityScale * 100f),
                                    onValueChangeFinished = {
                                        updateDensityScale(densityScaleDraft)
                                    },
                                    showKeyPoints = true,
                                    keyPoints = listOf(80f, 90f, 100f, 110f),
                                    magnetThreshold = 0.01f,
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                )
                            },
                            onClick = ::openDensityScaleDialog,
                            holdDownState = showDensityScaleDialog,
                        )
                    })
                },
            )

            item { SmallTitle(text = stringResource(R.string.settings_navigation_bar)) }
            groupedCardItems(
                keyPrefix = "theme_navigation",
                items = listOf(
                    CardItem("floating") {
                        SwitchPreference(
                            title = stringResource(R.string.settings_floating_bar),
                            summary = stringResource(R.string.settings_floating_bar_summary),
                            checked = themeConfig.floatingBottomBar,
                            onCheckedChange = { checked ->
                                updateTheme(themeConfig.copy(floatingBottomBar = checked))
                            },
                        )
                        AnimatedVisibility(
                            visible = themeConfig.floatingBottomBar,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_floating_bar_style),
                                summary = floatingBottomBarStyleItems.getOrElse(
                                    selectedFloatingBottomBarStyleIndex,
                                ) { floatingBottomBarStyleItems.first() },
                                items = floatingBottomBarStyleItems,
                                selectedIndex = selectedFloatingBottomBarStyleIndex,
                                onSelectedIndexChange = { index ->
                                    updateTheme(
                                        themeConfig.copy(
                                            floatingBottomBarStyle = floatingBottomBarStyles[index],
                                        ),
                                    )
                                },
                            )
                        }
                    },
                    CardItem("mode") {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_bar_mode),
                            summary = bottomBarModeItems.getOrElse(
                                selectedBottomBarModeIndex,
                            ) { bottomBarModeItems.first() },
                            items = bottomBarModeItems,
                            selectedIndex = selectedBottomBarModeIndex,
                            onSelectedIndexChange = { index ->
                                updateTheme(
                                    themeConfig.copy(
                                        bottomBarMode = bottomBarModes[index],
                                    ),
                                )
                            },
                        )
                    },
                ),
            )
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }

    DensityScaleDialog(
        show = showDensityScaleDialog,
        textState = densityScaleTextState,
        currentPercent = { densityScaleDraft },
        onDismiss = { showDensityScaleDialog = false },
        onConfirm = { percent ->
            updateDensityScale(percent)
            showDensityScaleDialog = false
        },
    )
}

@Composable
private fun DensityScaleDialog(
    show: Boolean,
    textState: TextFieldState,
    currentPercent: () -> Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.settings_density_scale),
        summary = stringResource(R.string.settings_density_scale_summary),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            inputTransformation = DigitsOnlyTransformation.maxLength(3),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                Text(
                    text = "%",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val percent = textState.text.toString().toIntOrNull()?.toFloat() ?: currentPercent()
                    onConfirm(percent)
                },
            )
        }
    }
}

private val DigitsOnlyTransformation = InputTransformation {
    if (!asCharSequence().all { it.isDigit() }) revertAllChanges()
}

private fun formatDensityScalePercent(value: Float): String = "${value.roundToInt()}%"
