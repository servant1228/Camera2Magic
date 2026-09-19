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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.BuildConfig
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.rememberConcentricCardRadius
import com.nothing.camera2magic.ui.theme.StatusColors
import com.nothing.camera2magic.utils.CameraInventory
import com.nothing.camera2magic.viewmodel.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

fun LazyListScope.statusSection(
    uiState: HomeUiState,
    scopeAppList: List<String>,
    onNavigateScope: () -> Unit = {},
) {
    item(key = "status") {
        StatusContent(uiState, scopeAppList, onNavigateScope)
    }
}

@Composable
private fun StatusContent(
    uiState: HomeUiState,
    scopeAppList: List<String>,
    onNavigateScope: () -> Unit,
) {
    val status = moduleStatus(uiState.xposedActive)
    val statusTint = StatusColors.runState(status.runState)
    val statusContainer = StatusColors.runStateContainer(status.runState)

    val context = LocalContext.current
    var showScopeDialog by remember { mutableStateOf(false) }
    // 镜头清点走 IO：getCameraIdList/getCameraCharacteristics 都是 binder 调用且会抛。
    // 读不到（部分机型把 LENS_FACING 列为受限键）时为 null，显示 —
    var lensCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        lensCount = withContext(Dispatchers.IO) { CameraInventory.count(context) }
    }

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
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = stringResource(R.string.home_lens),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lensCount?.toString() ?: "—",
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
}
