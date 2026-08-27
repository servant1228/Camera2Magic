package com.nothing.camera2magic.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.BuildConfig
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.rememberConcentricCardRadius
import com.nothing.camera2magic.ui.theme.StatusColors
import com.nothing.camera2magic.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

fun LazyListScope.statusSection(
    uiState: HomeUiState,
    scopeAppList: List<String>,
    onHookModeSelected: (String) -> Unit,
    onNavigateScope: () -> Unit = {},
) {
    item(key = "status") {
        StatusContent(uiState, scopeAppList, onHookModeSelected, onNavigateScope)
    }
}

@Composable
private fun StatusContent(
    uiState: HomeUiState,
    scopeAppList: List<String>,
    onHookModeSelected: (String) -> Unit,
    onNavigateScope: () -> Unit,
) {
    val status = moduleStatus(uiState.xposedActive)
    val statusTint = StatusColors.runState(status.runState)
    val statusContainer = StatusColors.runStateContainer(status.runState)

    var showHookModeDialog by remember { mutableStateOf(false) }
    var showScopeDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            cornerRadius = rememberConcentricCardRadius(),
            colors = CardDefaults.defaultColors(color = statusContainer),
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = status.icon,
                        tint = statusTint,
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 16.dp),
                ) {
                    Text(
                        text = stringResource(status.titleRes),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${uiState.versionName} (${BuildConfig.VERSION_CODE})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cornerRadius = rememberConcentricCardRadius(),
                insideMargin = PaddingValues(16.dp),
                onClick = { showHookModeDialog = true },
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = stringResource(R.string.home_hook_mode),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = uiState.hookMode,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cornerRadius = rememberConcentricCardRadius(),
                insideMargin = PaddingValues(16.dp),
                onClick = { onNavigateScope() },
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = stringResource(R.string.home_scope_apps),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (scopeAppList.isEmpty()) stringResource(R.string.home_no_scope_apps)
                    else scopeAppList.size.toString(),
                    fontSize = if (scopeAppList.isEmpty()) 13.sp else 20.sp,
                    fontWeight = if (scopeAppList.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }

    // 未激活提示优先；已激活但 scope 一个应用都没有时，hook 永远不会生效，同样给出引导
    val hintRes = when {
        status == ModuleStatus.Inactive -> R.string.home_status_inactive_hint
        scopeAppList.isEmpty() -> R.string.home_status_empty_scope_hint
        else -> null
    }
    hintRes?.let { res ->
        Text(
            text = stringResource(res),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }

    HookModeDialog(
        show = showHookModeDialog,
        currentMode = uiState.hookMode,
        onSelect = { mode ->
            onHookModeSelected(mode)
            showHookModeDialog = false
        },
        onDismiss = { showHookModeDialog = false },
    )
}

@Composable
private fun HookModeDialog(
    show: Boolean,
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val modes = remember { listOf("Camera1", "Camera2", "Camera3") }
    var selected by remember(show, currentMode) { mutableStateOf(currentMode) }

    WindowDialog(
        show = show,
        title = stringResource(R.string.home_hook_mode),
        onDismissRequest = onDismiss,
    ) {
        Column {
            modes.forEachIndexed { index, mode ->
                TextButton(
                    text = mode,
                    onClick = { selected = mode },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected == mode) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
                if (index < modes.lastIndex) Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { onSelect(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
