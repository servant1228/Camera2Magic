package com.nothing.camera2magic.ui.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.R
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.MediaType
import com.nothing.camera2magic.viewmodel.SpotlightViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun MediaSection(
    onPickMedia: (MediaType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val factory = LocalViewModelFactory.current
    val viewModel: SpotlightViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingType by remember { mutableStateOf<MediaType?>(null) }

    val pickMediaLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        pendingType?.let { type ->
            if (uri == null) return@let
            viewModel.onMediaSelected(type, uri)
        }
    }

    val pickMediaInternal = { type: MediaType ->
        pendingType = type
        val request = PickVisualMediaRequest(
            when (type) {
                MediaType.VIDEO -> PickVisualMedia.VideoOnly
                MediaType.IMAGE -> PickVisualMedia.ImageOnly
            }
        )
        pickMediaLauncher.launch(request)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        MediaType.entries.forEachIndexed { index, type ->
            MediaCard(
                mediaType = type,
                isSelected = uiState.currentType == type,
                filePath = viewModel.getMediaPath(type),
                isProcessing = type in uiState.processingMedia,
                onClick = { pickMediaInternal(type) },
                onClear = { viewModel.clearMediaBy(type) },
                onSelect = { viewModel.setCurrentMediaType(type) },
                modifier = if (index < MediaType.entries.lastIndex) Modifier.padding(bottom = 12.dp) else Modifier
            )
        }
    }
}

@Composable
private fun MediaCard(
    mediaType: MediaType,
    isSelected: Boolean,
    filePath: String?,
    isProcessing: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (mediaType) {
        MediaType.IMAGE -> stringResource(R.string.home_media_type_image)
        MediaType.VIDEO -> stringResource(R.string.home_media_type_video)
    }
    val summary = filePath ?: stringResource(R.string.spotlight_no_media_selected)

    fun handleClick() {
        if (isProcessing) return
        if (filePath != null) {
            onSelect()
        } else {
            onClick()
        }
    }

    fun handleLongClick() {
        if (filePath != null && !isProcessing) {
            onClear()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    enabled = !isProcessing,
                    onClick = ::handleClick,
                    onLongClick = ::handleLongClick
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = MiuixTheme.textStyles.main.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MiuixTheme.colorScheme.primary, CircleShape)
                    )
                }

                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
