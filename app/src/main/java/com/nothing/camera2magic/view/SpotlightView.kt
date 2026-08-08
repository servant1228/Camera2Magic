package com.nothing.camera2magic.view

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nothing.camera2magic.R
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.viewmodel.LocalViewModelFactory
import com.nothing.camera2magic.viewmodel.MediaType
import com.nothing.camera2magic.viewmodel.SpotlightViewModel
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SpotlightView() {
    val mediaTypes = MediaType.entries

    val factory = LocalViewModelFactory.current
    val viewModel: SpotlightViewModel = viewModel(factory = factory)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingType by remember { mutableStateOf<MediaType?>(null) }

    val pickMediaLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        pendingType?.let { type ->
            if (uri == null) return@let
            viewModel.updateThumbnailState(type, null)
            viewModel.onMediaSelected(type, uri)
        }
    }

    val pickMedia = { type: MediaType ->
        Dog.w("[Spotlight view]", "pick media: $type", true)
        pendingType = type
        val request = PickVisualMediaRequest(
            when (type) {
                MediaType.VIDEO -> PickVisualMedia.VideoOnly
                MediaType.IMAGE -> PickVisualMedia.ImageOnly
            }
        )
        pickMediaLauncher.launch(request)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        MediaPreviewGrid(
            mediaTypes = mediaTypes,
            thumbnails = uiState.thumbnails,
            currentType = uiState.currentType,
            processingMedia = uiState.processingMedia,
            onPickMedia = { type -> pickMedia(type) },
            onClearMedia = { type -> viewModel.clearMediaBy(type) },
            onTypeSelected = { type -> viewModel.setCurrentMediaType(type) }
        )
        ModuleSwitch(
            text = stringResource(R.string.module_switch_name),
            isEnabled = uiState.moduleEnabled,
            enabled = uiState.xposedActive,
            onToggle = { viewModel.onModuleToggled() }
        )
    }
    OnLifecycleEvent { event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            viewModel.performHealthCheckAndRefresh()
        }
    }
}

@Composable
private fun MediaPreviewGrid(
    mediaTypes: kotlin.enums.EnumEntries<MediaType>,
    thumbnails: Map<MediaType, Bitmap?>,
    currentType: MediaType,
    processingMedia: Set<MediaType>,
    onPickMedia: (MediaType) -> Unit,
    onClearMedia: (MediaType) -> Unit,
    onTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        mediaTypes.forEach { type ->
            Column(modifier = Modifier.weight(1f)) {
                MediaThumbnailCard(
                    thumbnail = thumbnails[type],
                    mediaType = type,
                    isProcessing = type in processingMedia,
                    isSelected = currentType == type,
                    onClick = { onPickMedia(type) },
                    onClear = { onClearMedia(type) },
                    onSelect = { onTypeSelected(type) }
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnailCard(
    modifier: Modifier = Modifier,
    mediaType: MediaType,
    thumbnail: Bitmap?,
    isProcessing: Boolean,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onClear: () -> Unit,
    onSelect: () -> Unit = {}
) {
    var inDeleteMode by remember { mutableStateOf(false) }

    fun handleOnclick() {
        if (isProcessing) return
        if (inDeleteMode) {
            inDeleteMode = false
        } else {
            onSelect()
        }
    }

    fun handleOnLongClick() {
        if (thumbnail != null && !isProcessing) {
            inDeleteMode = true
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .then(
                if (isSelected) {
                    Modifier
                        .squircleSurface(MiuixTheme.colorScheme.surfaceVariant, 12.dp)
                        .squircleBorder(width = 2.dp, color = MiuixTheme.colorScheme.primary, cornerRadius = 12.dp)
                } else {
                    Modifier.squircleSurface(MiuixTheme.colorScheme.surfaceVariant, 12.dp)
                }
            )
            .combinedClickable(
                enabled = !isProcessing,
                onClick = ::handleOnclick,
                onLongClick = ::handleOnLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        ThumbnailContent(thumbnail, mediaType)
        DeleteModeOverlay(visible = inDeleteMode && !isProcessing) {
            onClear()
            inDeleteMode = false
        }
        ProcessingOverlay(visible = isProcessing)
    }
}

@Composable
private fun ThumbnailContent(thumbnail: Bitmap?, mediaType: MediaType) {
    if (thumbnail != null) {
        androidx.compose.foundation.Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        val iconResource = if (mediaType == MediaType.VIDEO) {
            R.drawable.video_file_24px
        } else {
            R.drawable.image_24px
        }
        androidx.compose.foundation.Image(
            imageVector = ImageVector.vectorResource(iconResource),
            contentDescription = null,
            modifier = Modifier.scale(1.5f)
        )
    }
}

@Composable
private fun DeleteModeOverlay(visible: Boolean, onClear: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.windowDimming.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(28.dp),
                backgroundColor = MiuixTheme.colorScheme.windowDimming.copy(alpha = 0.4f)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.spotlight_delete_media),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ProcessingOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.windowDimming.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ModuleSwitch(
    text: String,
    isEnabled: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.developer_board_24px),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = text)
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
        }
    }
}

@Composable
fun OnLifecycleEvent(onEvent: (event: Lifecycle.Event) -> Unit) {
    val eventHandler by rememberUpdatedState(onEvent)
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> eventHandler(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
