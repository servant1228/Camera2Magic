package com.nothing.camera2magic.ui.screen.log

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.ListPopupDefaults
import com.nothing.camera2magic.ui.component.SearchBarFake
import com.nothing.camera2magic.ui.component.SearchBox
import com.nothing.camera2magic.ui.component.SearchPager
import com.nothing.camera2magic.ui.component.SearchStatus
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.theme.StatusColors
import com.nothing.camera2magic.utils.LogEntry
import com.nothing.camera2magic.viewmodel.LogViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun LogScreen(
    viewModel: LogViewModel,
    enableLog: Boolean = true,
    onEnableLog: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val autoScroll by remember {
        derivedStateOf {
            val i = listState.layoutInfo; val v = i.visibleItemsInfo
            v.isEmpty() || v.last().index >= i.totalItemsCount - 2
        }
    }
    val lastId = logs.lastOrNull()?.id
    val selectedLevels = remember { mutableStateListOf("I", "W", "E") }

    LaunchedEffect(lastId) { if (lastId != null && autoScroll) listState.animateScrollToItem(logs.lastIndex) }

    val searchLabel = stringResource(R.string.log_title)
    var searchStatus by remember { mutableStateOf(SearchStatus(label = searchLabel)) }
    var selectedEntry by remember { mutableStateOf<LogEntry?>(null) }

    val searchText = searchStatus.searchText
    val filtered = logs.filter {
        (searchText.isEmpty() || it.tag.contains(searchText, ignoreCase = true) ||
                it.message.contains(searchText, ignoreCase = true)) &&
                it.level in selectedLevels
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

    fun onSearchStatusChange(next: SearchStatus) {
        searchStatus = next.copy(
            resultStatus = if (next.searchText.isBlank()) SearchStatus.ResultStatus.DEFAULT
            else SearchStatus.ResultStatus.SHOW
        )
    }

    LogDetailDialog(
        show = selectedEntry != null,
        entry = selectedEntry,
        onDismiss = { selectedEntry = null },
    )

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.log_title),
                        navigationIcon = {},
                        actions = {
                            IconButton(
                                onClick = { viewModel.clear() },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.common_clear),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                            Box {
                                val showFilterPopup = remember { mutableStateOf(false) }
                                OverlayListPopup(
                                    show = showFilterPopup.value,
                                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showFilterPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        val levels = listOf("I", "W", "E")
                                        levels.forEachIndexed { index, level ->
                                            DropdownImpl(
                                                text = level,
                                                isSelected = level in selectedLevels,
                                                optionSize = levels.size,
                                                onSelectedIndexChange = {
                                                    if (level in selectedLevels) selectedLevels.remove(level)
                                                    else selectedLevels.add(level)
                                                },
                                                index = index,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { showFilterPopup.value = true },
                                    holdDownState = showFilterPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Filter,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
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
                                SearchBarFake("", dynamicTopPadding)
                            }
                        },
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = ::onSearchStatusChange,
                defaultResult = {},
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .navigationBarsPadding(),
                ) {
                    item { Spacer(Modifier.height(12.dp)) }
                    logContentSection(filtered, selectedEntry, enableLog, onEnableLog) { selectedEntry = it }
                    item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
                }
            }
        },
    ) { innerPadding ->
        searchStatus.SearchBox {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
            ) {
                item { Spacer(Modifier.height(12.dp)) }
                logContentSection(logs, selectedEntry, enableLog, onEnableLog) { selectedEntry = it }
                item(key = "bottom") {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}

private fun LazyListScope.logContentSection(
    entries: List<LogEntry>,
    selectedEntry: LogEntry?,
    enableLog: Boolean,
    onEnableLog: () -> Unit,
    onEntryClick: (LogEntry) -> Unit,
) {
    if (!enableLog) {
        item(key = "log_disabled") {
            LogDisabledSection(onEnable = onEnableLog)
        }
        return
    }
    if (entries.isEmpty()) {
        item(key = "empty") {
            Box(
                modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.log_empty),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        return
    }
    items(entries, key = { it.id }) { entry ->
        LogEntryCard(entry = entry, onClick = { onEntryClick(entry) })
    }
}

@Composable
private fun LogDisabledSection(onEnable: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.log_disabled_title),
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            TextButton(
                text = stringResource(R.string.log_enable_action),
                onClick = onEnable,
                minHeight = 28.dp,
                cornerRadius = 50.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: LogEntry,
    onClick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        clipboard.setText(AnnotatedString(entry.message))
                        Toast.makeText(context, R.string.common_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    },
                )
            },
        onClick = onClick,
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.level,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = StatusColors.logLevel(entry.level),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.message,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun LogDetailDialog(
    show: Boolean,
    entry: LogEntry?,
    onDismiss: () -> Unit,
) {
    var lastEntry by remember { mutableStateOf(entry) }
    if (entry != null) lastEntry = entry
    val displayEntry = lastEntry ?: return
    OverlayDialog(
        show = show,
        title = "${displayEntry.level}  ${displayEntry.tag}",
        onDismissRequest = onDismiss,
        content = {
            Column {
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = displayEntry.message,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(android.R.string.ok),
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
