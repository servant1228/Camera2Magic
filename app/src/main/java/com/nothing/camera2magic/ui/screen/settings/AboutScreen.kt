package com.nothing.camera2magic.ui.screen.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.BuildConfig
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.theme.LocalAppDarkMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class ProjectLink(
    val title: String,
    val summary: String,
    val url: String,
)

private val projectLinks = listOf(
    ProjectLink(
        title = "Camera2 Magic",
        summary = "github.com/servant1228/Camera2Magic",
        url = "https://github.com/servant1228/Camera2Magic",
    ),
    ProjectLink(
        title = "libxposed",
        summary = "github.com/libxposed/api",
        url = "https://github.com/libxposed/api",
    ),
    ProjectLink(
        title = "miuix",
        summary = "github.com/compose-miuix-ui/miuix",
        url = "https://github.com/compose-miuix-ui/miuix",
    ),
    ProjectLink(
        title = "media3",
        summary = "github.com/androidx/media",
        url = "https://github.com/androidx/media",
    ),
    ProjectLink(
        title = "hiddenapibypass",
        summary = "github.com/LSPosed/hiddenapibypass",
        url = "https://github.com/LSPosed/hiddenapibypass",
    ),
)

@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val uriHandler = LocalUriHandler.current
    var headerHeightDp by remember { mutableStateOf(300.dp) }

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f

                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null && scrollProgress == 1f
    val barColor = when {
        blurActive -> Color.Transparent
        scrollProgress == 1f -> MiuixTheme.colorScheme.surface
        else -> Color.Transparent
    }
    val titleAlpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val iconProgress = ((scrollProgress - 0.30f) / 0.30f).coerceIn(0f, 1f)
    val nameProgress = ((scrollProgress - 0.15f) / 0.30f).coerceIn(0f, 1f)
    val versionProgress = (scrollProgress / 0.40f).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.about_title),
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = titleAlpha),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AboutBackground(contentAlpha = 1f - scrollProgress)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = innerPadding.calculateTopPadding() + 40.dp,
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                    )
                    .onSizeChanged { size ->
                        with(density) { headerHeightDp = size.height.toDp() }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            alpha = 1f - iconProgress
                            scaleX = 1f - iconProgress * 0.05f
                            scaleY = 1f - iconProgress * 0.05f
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(96.dp),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 5.dp)
                        .graphicsLayer {
                            alpha = 1f - nameProgress
                            scaleX = 1f - nameProgress * 0.05f
                            scaleY = 1f - nameProgress * 0.05f
                        },
                    color = MiuixTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 35.sp,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = 1f - versionProgress },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
            ) {
                item(key = "logoSpacer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerHeightDp + 96.dp),
                    )
                }

                item(key = "aboutInfo") {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        SmallTitle(text = stringResource(R.string.about_info))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
                            ),
                        ) {
                            BasicComponent(
                                title = stringResource(R.string.about_app_version),
                                summary = BuildConfig.VERSION_NAME,
                            )
                            BasicComponent(
                                title = stringResource(R.string.about_build_version),
                                summary = BuildConfig.VERSION_CODE.toString(),
                            )
                        }
                    }
                }

                item(key = "aboutProject") {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        SmallTitle(text = stringResource(R.string.about_project))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
                            ),
                        ) {
                            projectLinks.forEach { link ->
                                ArrowPreference(
                                    title = link.title,
                                    summary = link.summary,
                                    onClick = { uriHandler.openUri(link.url) },
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
                }
            }
        }
    }
}

@Composable
private fun AboutBackground(contentAlpha: Float) {
    val isDark = LocalAppDarkMode.current
    val colors = remember(isDark) {
        if (isDark) {
            listOf(Color(0xFF2B3A8F), Color(0xFF6D2E8F), Color(0xFF0F6E7F))
        } else {
            listOf(Color(0xFF7FB2FF), Color(0xFFFF9ECB), Color(0xFF7EE0B8))
        }
    }

    val transition = rememberInfiniteTransition(label = "aboutBackground")
    val driftA by transition.animateFloat(
        initialValue = -0.18f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftA",
    )
    val driftB by transition.animateFloat(
        initialValue = -0.12f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftB",
    )
    val driftC by transition.animateFloat(
        initialValue = -0.08f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftC",
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = contentAlpha },
    ) {
        val radius = size.minDimension * 0.55f
        val centers = listOf(
            Offset(size.width * (0.25f + driftA), size.height * (0.22f + driftB)),
            Offset(size.width * (0.78f + driftB), size.height * (0.18f - driftA)),
            Offset(size.width * (0.50f + driftC), size.height * (0.58f - driftB)),
        )
        colors.forEachIndexed { index, color ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(radius, radius),
                    radius = radius,
                ),
                radius = radius,
                center = centers[index],
            )
        }
    }
}
