package com.nothing.camera2magic.ui.screen.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.theme.ActionKind
import com.nothing.camera2magic.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ActionButtonsSection(
    moduleEnabled: Boolean,
    xposedActive: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!xposedActive) {
        Text(
            text = stringResource(R.string.home_status_inactive_hint),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        return
    }
    if (moduleEnabled) {
        TextButton(
            text = stringResource(R.string.home_deactivate_module),
            onClick = onDeactivate,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            colors = ButtonDefaults.textButtonColors(
                color = StatusColors.actionButtonContainer(ActionKind.Stop),
                textColor = StatusColors.actionButtonContent(ActionKind.Stop),
            ),
        )
    } else {
        TextButton(
            text = stringResource(R.string.home_activate_module),
            onClick = onActivate,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}
