package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardItem
import com.nothing.camera2magic.ui.component.groupedCardItems
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.ui.util.horizontalCutoutPadding
import com.nothing.camera2magic.utils.LocalAppIconResolver
import com.nothing.camera2magic.utils.LocalInstalledIconPacks
import com.nothing.camera2magic.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun IconPackSettingsScreen(
    viewModel: SettingsViewModel,
    onThemeConfigChanged: (ThemeConfig) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeConfig = uiState.themeConfig
    val installedIconPacks = LocalInstalledIconPacks.current
    val resolver = LocalAppIconResolver.current
    val scrollBehavior = MiuixScrollBehavior()

    // 后台预热所选图标包的 appfilter 解析，避免返回作用域列表时首帧卡顿
    LaunchedEffect(themeConfig.iconPack) {
        if (themeConfig.iconPack.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                resolver.load(themeConfig.iconPack, themeConfig.iconPack, 96)
            }
        }
    }

    fun updateTheme(next: ThemeConfig) {
        viewModel.onThemeConfigChanged(next)
        onThemeConfigChanged(next)
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_icon_pack),
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
            item { SmallTitle(text = stringResource(R.string.settings_icon_pack)) }
            groupedCardItems(
                keyPrefix = "icon_pack",
                items = buildList {
                    add(CardItem("default") {
                        RadioButtonPreference(
                            title = stringResource(R.string.settings_icon_pack_default),
                            summary = stringResource(R.string.settings_icon_pack_default_summary),
                            selected = themeConfig.iconPack.isEmpty(),
                            onClick = { updateTheme(themeConfig.copy(iconPack = "")) },
                            radioButtonLocation = RadioButtonLocation.End,
                        )
                    })
                    installedIconPacks.forEach { pack ->
                        add(CardItem(pack.packageName) {
                            RadioButtonPreference(
                                title = pack.label,
                                summary = pack.packageName,
                                selected = themeConfig.iconPack == pack.packageName,
                                onClick = { updateTheme(themeConfig.copy(iconPack = pack.packageName)) },
                                radioButtonLocation = RadioButtonLocation.End,
                                startAction = { IconPackAvatar(pack.packageName) },
                            )
                        })
                    }
                },
            )
            if (installedIconPacks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_icon_pack_empty),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun IconPackAvatar(packageName: String) {
    val resolver = LocalAppIconResolver.current
    val sizePx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val bitmap = remember(packageName, sizePx) { resolver.load(null, packageName, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            filterQuality = FilterQuality.High,
        )
    } else {
        Spacer(Modifier.size(36.dp))
    }
}
