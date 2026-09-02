package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardItem
import com.nothing.camera2magic.ui.component.groupedCardItems
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 一条开放源代码许可记录。
 * [license] 为 SPDX 标识（无标准标识时用可读名称），[url] 是该项目主页。
 */
private class LicenseEntry(
    val name: String,
    val license: String,
    val url: String,
)

/** 随 APK 分发的第三方库；坐标真源见 gradle/libs.versions.toml（测试/调试期依赖不入包，不列） */
private val LibraryLicenses = listOf(
    LicenseEntry("Jetpack Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX Core KTX", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX Lifecycle", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX Activity Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX NavigationEvent", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenseEntry("AndroidX Media3 (ExoPlayer)", "Apache-2.0", "https://github.com/androidx/media"),
    LicenseEntry("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    LicenseEntry("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LicenseEntry("Miuix", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
    LicenseEntry("AndroidLiquidGlass", "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass"),
    LicenseEntry("libxposed API", "Apache-2.0", "https://github.com/libxposed/api"),
    LicenseEntry("libxposed Service", "Apache-2.0", "https://github.com/libxposed/service"),
    LicenseEntry("AndroidHiddenApiBypass", "Apache-2.0", "https://github.com/LSPosed/AndroidHiddenApiBypass"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // libcamera3.so 内的原生组件与上游项目：不是 Gradle 依赖，但同样需要标注许可
    val noLicenseLabel = stringResource(R.string.licenses_no_license)
    val otherLicenses = remember(noLicenseLabel) {
        listOf(
            LicenseEntry(
                name = "libjpeg-turbo",
                license = "IJG / BSD-3-Clause / zlib",
                url = "https://libjpeg-turbo.org/",
            ),
            LicenseEntry(
                name = "Camera2Magic (Atomos-X)",
                license = noLicenseLabel,
                url = "https://github.com/Atomos-X/Camera2Magic",
            ),
        )
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.about_licenses),
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
            item { SmallTitle(text = stringResource(R.string.licenses_libraries)) }
            licenseCardItems(keyPrefix = "licenses_library", entries = LibraryLicenses) { url ->
                uriHandler.openUri(url)
            }

            item { SmallTitle(text = stringResource(R.string.licenses_other)) }
            licenseCardItems(keyPrefix = "licenses_other", entries = otherLicenses) { url ->
                uriHandler.openUri(url)
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

private fun LazyListScope.licenseCardItems(
    keyPrefix: String,
    entries: List<LicenseEntry>,
    onOpenUrl: (String) -> Unit,
) {
    groupedCardItems(
        keyPrefix = keyPrefix,
        items = entries.map { entry ->
            CardItem(entry.name) {
                ArrowPreference(
                    title = entry.name,
                    summary = "${entry.license} · ${entry.displayUrl}",
                    onClick = { onOpenUrl(entry.url) },
                )
            }
        },
    )
}

private val LicenseEntry.displayUrl: String
    get() = url.removePrefix("https://").removeSuffix("/")
