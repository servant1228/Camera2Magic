package com.nothing.camera2magic.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val WideScreenMinWidth = 600
const val MaxContentWidth = 800

@Composable
fun rememberIsWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.screenWidthDp >= WideScreenMinWidth }
}

@Composable
fun WideContentBox(
    sidePadding: Dp = 0.dp,
    content: @Composable (Dp) -> Unit
) {
    val isWideScreen = rememberIsWideScreen()
    val actualPadding = if (isWideScreen) sidePadding else 0.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = actualPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            content(actualPadding)
        }
    }
}
