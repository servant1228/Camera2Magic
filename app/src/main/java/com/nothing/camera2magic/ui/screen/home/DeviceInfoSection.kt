package com.nothing.camera2magic.ui.screen.home

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.rememberConcentricCardRadius
import com.nothing.camera2magic.ui.util.DeviceName
import com.nothing.camera2magic.viewmodel.LocalConfigRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DeviceInfoCard(modifier: Modifier = Modifier) {
    val model = DeviceName.display()
    val systemInfo = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    // 服务绑定后为 "LSPosed 1.10.1 (10798)"；未绑定显示 —
    val lsposedInfo by LocalConfigRepository.current.frameworkInfo.collectAsStateWithLifecycle()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        cornerRadius = rememberConcentricCardRadius(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.home_device_model),
                fontSize = MiuixTheme.textStyles.main.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = model,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.home_device_system),
                fontSize = MiuixTheme.textStyles.main.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = systemInfo,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.home_device_lsposed),
                fontSize = MiuixTheme.textStyles.main.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = lsposedInfo ?: "—",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.home_device_architecture),
                fontSize = MiuixTheme.textStyles.main.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = abi,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
