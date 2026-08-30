package com.nothing.camera2magic

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.LocalBlurEnabled
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.navigation3.LocalNavigator
import com.nothing.camera2magic.ui.navigation3.Navigator
import com.nothing.camera2magic.ui.navigation3.Route
import com.nothing.camera2magic.ui.screen.home.DeviceInfoCard
import com.nothing.camera2magic.ui.screen.home.statusSection
import com.nothing.camera2magic.ui.screen.scope.ScopeScreen
import com.nothing.camera2magic.ui.screen.scope.AppConfigScreen
import com.nothing.camera2magic.ui.component.liquid.IosLiquidGlassNavigationBar
import com.nothing.camera2magic.ui.screen.settings.AboutScreen
import com.nothing.camera2magic.ui.screen.settings.SettingsPage
import com.nothing.camera2magic.ui.screen.settings.SettingsScreenContent
import com.nothing.camera2magic.ui.screen.settings.ThemeSettingsScreen
import com.nothing.camera2magic.ui.theme.BottomBarMode
import com.nothing.camera2magic.ui.theme.Camera2MagicTheme
import com.nothing.camera2magic.ui.theme.FloatingBottomBarStyle
import com.nothing.camera2magic.ui.theme.LocalAppDarkMode
import com.nothing.camera2magic.ui.theme.LocalThemeConfig
import com.nothing.camera2magic.ui.theme.ThemeConfig
import com.nothing.camera2magic.ui.theme.readThemeConfig
import com.nothing.camera2magic.ui.theme.writeThemeConfig
import com.nothing.camera2magic.viewmodel.ConfigRepository
import com.nothing.camera2magic.viewmodel.HomeViewModel
import com.nothing.camera2magic.viewmodel.LocalConfigRepository
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.SettingsViewModel
import com.nothing.camera2magic.viewmodel.ViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("camera_magic_config", MODE_PRIVATE)
        enableEdgeToEdge()
        // 启动时启用预测性返回手势
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val predictiveBack = prefs.getBoolean("theme_predictive_back", true)
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, predictiveBack)
        }
        setContent {
            val repository = remember { ConfigRepository(prefs) }
            val initialConfig = remember { readThemeConfig(repository) }
            var themeConfig by remember { mutableStateOf(initialConfig) }
            val factory = remember { ViewModelFactory(application, repository) }
            Camera2MagicTheme(themeConfig = themeConfig) {
                CompositionLocalProvider(
                    LocalConfigRepository provides repository,
                    LocalThemeConfig provides themeConfig,
                    LocalViewModelFactory provides factory,
                    LocalBlurEnabled provides themeConfig.blurEnabled,
                ) {
                    AppNavigation(themeConfig = themeConfig, onThemeConfigChanged = { config ->
                        val changed = themeConfig.predictiveBack != config.predictiveBack
                        themeConfig = config
                        writeThemeConfig(repository, config)
                        if (changed) {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                setEnableOnBackInvokedCallback(applicationInfo, config.predictiveBack)
                            }
                            recreateWithoutTransition()
                        }
                    })
                }
            }
        }
    }

    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: android.content.pm.ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navigator: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navigator.current() is Route.Main &&
                navigator.backStackSize() == 1 &&
                mainState.selectedPage != 0
        }
    }
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = { mainState.animateToPage(0) },
    )
}

@Composable
private fun AppNavigation(themeConfig: ThemeConfig, onThemeConfigChanged: (ThemeConfig) -> Unit) {
    // rememberNavBackStack 内置 JSON 多态持久化（等价于原手写 NavBackStackSaver）
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val mainPagerState = rememberMainPagerState(pagerState)
    val selectedPage = mainPagerState.selectedPage

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navigator)

    val barMode = if (themeConfig.bottomBarMode == BottomBarMode.IconOnly)
        NavigationBarDisplayMode.IconOnly else NavigationBarDisplayMode.IconAndText
    val showLabels = barMode == NavigationBarDisplayMode.IconAndText

    val bottomBarBackdrop = rememberBlurBackdrop()
    val bottomBarBlurActive = bottomBarBackdrop != null
    val bottomBarColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val floatingBarColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
    val floatingPillRadius = 50.dp
    val floatingBarShape = remember(floatingPillRadius) { SquirclePillShape(floatingPillRadius) }
    val floatingBarBlurModifier = if (bottomBarBackdrop != null) {
        Modifier.textureBlur(
            backdrop = bottomBarBackdrop,
            shape = floatingBarShape,
            blurRadius = 25f,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f)),
                ),
            ),
        )
    } else {
        Modifier
    }

    val navigationItems = listOf(
        NavigationItem(label = stringResource(R.string.nav_home), icon = MiuixIcons.Home),
        NavigationItem(label = stringResource(R.string.nav_scope), icon = MiuixIcons.GridView),
        NavigationItem(label = stringResource(R.string.nav_settings), icon = MiuixIcons.Settings),
    )

    val factory = LocalViewModelFactory.current
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<Route.Main> {
                val pagerContent: @Composable (Modifier, Dp) -> Unit = { pagerModifier, bottomPadding ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = pagerModifier,
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        when (page) {
                            0 -> HomePage(bottomPadding = bottomPadding, onNavigateScope = { mainPagerState.animateToPage(1) })
                            1 -> ScopePage(bottomPadding = bottomPadding)
                            2 -> SettingsScreenContent(
                                viewModel = settingsViewModel,
                                onThemeConfigChanged = onThemeConfigChanged,
                                bottomPadding = bottomPadding,
                                onNavigateThemeSettings = { navigator.push(Route.ThemeSettings) },
                                onNavigateAbout = { navigator.push(Route.About) },
                            )
                        }
                    }
                }

                Scaffold(
                    bottomBar = {
                        if (themeConfig.floatingBottomBar) {
                            if (themeConfig.floatingBottomBarStyle == FloatingBottomBarStyle.IosLike) {
                                IosLiquidGlassNavigationBar(
                                    items = navigationItems,
                                    selectedIndex = selectedPage,
                                    onItemClick = { index -> mainPagerState.animateToPage(index) },
                                    backdrop = bottomBarBackdrop,
                                    isBlurActive = bottomBarBlurActive,
                                    isDark = LocalAppDarkMode.current,
                                    showLabels = showLabels,
                                )
                            } else {
                                FloatingNavigationBar(
                                    modifier = floatingBarBlurModifier,
                                    color = floatingBarColor,
                                    cornerRadius = floatingPillRadius,
                                ) {
                                    navigationItems.forEachIndexed { index, item ->
                                        MiuixFloatingNavigationBarItem(item = item, selected = selectedPage == index, onClick = { mainPagerState.animateToPage(index) }, showLabel = showLabels)
                                    }
                                }
                            }
                        } else {
                            BlurredBar(backdrop = bottomBarBackdrop, blurActive = bottomBarBlurActive) {
                                NavigationBar(color = bottomBarColor, mode = barMode) {
                                    navigationItems.forEachIndexed { index, item ->
                                        NavigationBarItem(selected = selectedPage == index, onClick = { mainPagerState.animateToPage(index) }, icon = item.icon, label = item.label)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { padding ->
                    pagerContent(
                        if (bottomBarBackdrop != null) {
                            Modifier.fillMaxSize().layerBackdrop(bottomBarBackdrop)
                        } else {
                            Modifier.fillMaxSize()
                        },
                        padding.calculateBottomPadding(),
                    )
                }
            }

            entry<Route.ThemeSettings> {
                ThemeSettingsScreen(
                    viewModel = settingsViewModel,
                    onThemeConfigChanged = onThemeConfigChanged,
                    onBack = { navigator.pop() },
                )
            }

            entry<Route.About> {
                AboutScreen(onBack = { navigator.pop() })
            }

            entry<Route.AppConfig> { key ->
                val context = LocalContext.current
                val repository = LocalConfigRepository.current
                val label = remember(key.packageName) {
                    runCatching {
                        val pm = context.packageManager
                        val info = pm.getApplicationInfo(key.packageName, 0)
                        pm.getApplicationLabel(info).toString()
                    }.getOrDefault(key.packageName)
                }
                AppConfigScreen(
                    packageName = key.packageName,
                    appLabel = label,
                    repository = repository,
                    onBack = { navigator.pop() },
                )
            }
        }
    }
}

@Composable
private fun MiuixFloatingNavigationBarItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = if (showLabel) null else item.label,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = item.label,
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Squircle 形状（与 Miuix `squircleBackground` 同源的连续圆角），
 * 用于悬浮底栏毛玻璃的模糊区域裁剪。
 */
private class SquirclePillShape(private val cornerRadius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { cornerRadius.toPx() }
        val path = Path().apply {
            addSquircleRect(
                width = size.width,
                height = size.height,
                cornerRadius = radius,
            )
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun HomePage(bottomPadding: Dp = 0.dp, onNavigateScope: () -> Unit = {}) {
    val themeConfig = LocalThemeConfig.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val factory = LocalViewModelFactory.current
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.app_name),
                    scrollBehavior = scrollBehavior,
                    color = barColor,
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
            statusSection(
                uiState = uiState,
                scopeAppList = uiState.scopeAppList,
                onHookModeSelected = { homeViewModel.onHookModeChanged(it) },
                onNavigateScope = onNavigateScope,
            )
            item { DeviceInfoCard() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ScopePage(bottomPadding: Dp = 0.dp) {
    val repository = LocalConfigRepository.current
    val navigator = LocalNavigator.current
    ScopeScreen(
        bottomPadding = bottomPadding,
        onNavigateAppConfig = { pkg -> navigator.push(Route.AppConfig(pkg)) },
    )
}

@Stable
private class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val distance = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
                    val duration = 100 * distance + 100
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize

                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }

                if (pagerState.currentPage != targetIndex) {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
private fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): MainPagerState = remember(pagerState, coroutineScope) {
    MainPagerState(pagerState, coroutineScope)
}
