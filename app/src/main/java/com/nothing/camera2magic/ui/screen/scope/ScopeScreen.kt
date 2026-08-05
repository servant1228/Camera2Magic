package com.nothing.camera2magic.ui.screen.scope

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.SearchBarFake
import com.nothing.camera2magic.ui.component.SearchBox
import com.nothing.camera2magic.ui.component.SearchPager
import com.nothing.camera2magic.ui.component.SearchStatus
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.viewmodel.HomeViewModel
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

data class ScopeApp(val packageName: String, val label: String, val isSystem: Boolean, val mediaMode: String = "global")

@Composable
fun ScopeScreen(
    onNavigateAppConfig: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val factory = LocalViewModelFactory.current
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current

    val allApps = remember(uiState.scopeAppList) {
        loadScopeApps(context, uiState.scopeAppList, emptyMap())
    }

    val displayedApps = allApps.filter { !it.isSystem }

    val searchLabel = stringResource(R.string.home_scope_apps)
    var searchStatus by remember { mutableStateOf(SearchStatus(label = searchLabel)) }

    LaunchedEffect(searchLabel) {
        if (searchStatus.label != searchLabel) {
            searchStatus = searchStatus.copy(label = searchLabel)
        }
    }

    val searchText = searchStatus.searchText
    val filtered = displayedApps.filter {
        searchText.isEmpty() || it.label.contains(searchText, ignoreCase = true) || it.packageName.contains(searchText, ignoreCase = true)
    }

    val resultStatus by remember(searchText, filtered) {
        derivedStateOf {
            when {
                searchText.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
                filtered.isEmpty() -> SearchStatus.ResultStatus.EMPTY
                else -> SearchStatus.ResultStatus.SHOW
            }
        }
    }
    LaunchedEffect(resultStatus) {
        if (searchStatus.resultStatus != resultStatus) {
            searchStatus = searchStatus.copy(resultStatus = resultStatus)
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.home_scope_apps),
                        color = barColor,
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            val newOffsetY = coordinates.positionInWindow().y.toDp()
                                            if (searchStatus.offsetY != newOffsetY) {
                                                searchStatus = searchStatus.copy(offsetY = newOffsetY)
                                            }
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    searchStatus = searchStatus.copy(current = SearchStatus.Status.EXPANDING)
                                                }
                                            }
                                        } else Modifier,
                                    ),
                            ) {
                                SearchBarFake("")
                            }
                        },
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = { searchStatus = it },
                defaultResult = {},
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .navigationBarsPadding(),
                ) {
                    item { Spacer(Modifier.height(12.dp)) }
                    if (filtered.isEmpty()) {
                        item(key = "search_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.log_empty),
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            ScopeAppCard(app = app, onClick = { onNavigateAppConfig(app.packageName) })
                        }
                    }
                    item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        searchStatus.SearchBox {
            val listState = rememberLazyListState()
            val pullToRefreshState = rememberPullToRefreshState()

            PullToRefresh(
                isRefreshing = uiState.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = { homeViewModel.refreshScopeList() },
                refreshTexts = listOf("", "松手刷新", "刷新中...", "刷新完成"),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
            ) {
                Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                    LazyColumn(
                        state = listState,
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
                        item { Spacer(Modifier.height(12.dp)) }
                        if (displayedApps.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_no_scope_apps),
                                        fontSize = 16.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        } else {
                            items(displayedApps, key = { it.packageName }) { app ->
                                ScopeAppCard(app = app, onClick = { onNavigateAppConfig(app.packageName) })
                            }
                        }
                        item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeAppCard(app: ScopeApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        insideMargin = PaddingValues(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = loadAppIcon(LocalContext.current, app.packageName)
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp).size(48.dp),
                )
            } else {
                Box(Modifier.padding(end = 12.dp).size(48.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

private fun loadScopeApps(context: android.content.Context, scopePackages: List<String>, mediaModes: Map<String, String>): List<ScopeApp> {
    if (scopePackages.isEmpty()) return emptyList()
    val pm = context.packageManager
    return runCatching {
        scopePackages.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                ScopeApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    mediaMode = mediaModes[pkg] ?: "global",
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

internal fun loadAppIcon(context: android.content.Context, packageName: String): Bitmap? {
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(Canvas(bitmap))
        }
    }.getOrNull()
}
