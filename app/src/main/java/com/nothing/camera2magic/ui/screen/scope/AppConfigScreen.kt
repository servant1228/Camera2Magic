package com.nothing.camera2magic.ui.screen.scope

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardSegment
import com.nothing.camera2magic.ui.component.ListPopupDefaults
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.component.rememberConcentricCardRadius
import com.nothing.camera2magic.utils.MediaPathResolver
import com.nothing.camera2magic.viewmodel.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import com.nothing.camera2magic.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class MediaMode { PHOTO, VIDEO }

@Composable
fun AppConfigScreen(
    packageName: String,
    appLabel: String,
    repository: ConfigRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scope = rememberCoroutineScope()

    var hookEnabled by remember { mutableStateOf(repository.getAppHookEnabled(packageName)) }
    val initMode = when (repository.getAppMediaMode(packageName)) {
        "video" -> MediaMode.VIDEO
        else -> MediaMode.PHOTO
    }
    var mediaMode by remember { mutableStateOf(initMode) }
    var photoUri by remember { mutableStateOf(repository.getAppPhotoUri(packageName)) }
    var videoUri by remember { mutableStateOf(repository.getAppVideoUri(packageName)) }
    var photoDisplayPath by remember { mutableStateOf<String?>(null) }
    var videoDisplayPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(photoUri) {
        photoDisplayPath = photoUri?.let { uriString ->
            withContext(Dispatchers.IO) {
                MediaPathResolver.resolveDisplayPath(context, uriString.toUri())
            }
        }
    }
    LaunchedEffect(videoUri) {
        videoDisplayPath = videoUri?.let { uriString ->
            withContext(Dispatchers.IO) {
                MediaPathResolver.resolveDisplayPath(context, uriString.toUri())
            }
        }
    }

    var pendingMediaMode by remember { mutableStateOf<MediaMode?>(null) }
    // 每模式一个拷贝任务：删除/重选时 cancel 旧任务，防止「删除后拷贝完成又写回远端键」的竞态
    // copyJob 仍指向本任务（未被 cancel/替换）才回滚，说明失败态对应当前 UI 状态
    var copyJob by remember { mutableStateOf<Pair<MediaMode, Job>?>(null) }

    fun copyToRemote(uri: Uri, mode: MediaMode) {
        copyJob?.second?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val mimeType = context.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            val fileName = if (extension != null) "${mode.name.lowercase()}_$packageName.$extension" else "${mode.name.lowercase()}_$packageName"
            val success = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    repository.prepareRemoteMedia(fileName, input)
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (success) {
                    when (mode) {
                        MediaMode.PHOTO -> repository.setAppRemotePhoto(packageName, fileName)
                        MediaMode.VIDEO -> repository.setAppRemoteVideo(packageName, fileName)
                    }
                } else if (copyJob?.first == mode) {
                    when (mode) {
                        MediaMode.PHOTO -> {
                            photoUri = null
                            repository.setAppPhotoUri(packageName, null)
                        }
                        MediaMode.VIDEO -> {
                            videoUri = null
                            repository.setAppVideoUri(packageName, null)
                        }
                    }
                    Toast.makeText(context, context.getString(R.string.app_config_media_copy_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        copyJob = mode to job
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val mode = pendingMediaMode ?: return@rememberLauncherForActivityResult
        pendingMediaMode = null
        if (uri == null) return@rememberLauncherForActivityResult
        // Photo Picker 的 URI 在部分 ROM（API 33）不可持久化授权，失败不致命：媒体已被拷贝转存，URI 仅本进程内使用
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (mode) {
            MediaMode.PHOTO -> {
                photoUri = uri.toString()
                repository.setAppPhotoUri(packageName, uri.toString())
            }
            MediaMode.VIDEO -> {
                videoUri = uri.toString()
                repository.setAppVideoUri(packageName, uri.toString())
            }
        }
        copyToRemote(uri, mode)
    }

    // 清空某模式的本地 + 远端媒体状态；拷贝进行中则先取消，避免完成回调把已删除的状态写回
    fun clearMedia(mode: MediaMode) {
        copyJob?.takeIf { it.first == mode }?.second?.cancel()
        when (mode) {
            MediaMode.PHOTO -> {
                repository.getAppRemotePhoto(packageName)?.let { repository.deleteRemoteMedia(it) }
                repository.setAppRemotePhoto(packageName, null)
                photoUri = null
                repository.setAppPhotoUri(packageName, null)
            }
            MediaMode.VIDEO -> {
                repository.getAppRemoteVideo(packageName)?.let { repository.deleteRemoteMedia(it) }
                repository.setAppRemoteVideo(packageName, null)
                videoUri = null
                repository.setAppVideoUri(packageName, null)
            }
        }
    }

    // su 命令统一走 IO 线程并判定 exit code；su 不存在/被拒都返回 false，绝不给假的成功提示
    suspend fun runSuCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // 消费输出防止管道写满阻塞
            process.inputStream.use { it.readBytes() }
            process.errorStream.use { it.readBytes() }
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    fun isAppRunning(): Boolean = runCatching {
        // /proc/<pid>/cmdline 第一段即进程名，无需 ps 解析
        java.io.File("/proc").listFiles()
            ?.any { dir ->
                dir.isDirectory && dir.name.all { c -> c.isDigit() } &&
                    java.io.File(dir, "cmdline").inputStream().use {
                        it.readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
                    } == packageName
            } == true
    }.getOrDefault(false)

    val onLaunchApp: () -> Unit = {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) context.startActivity(intent)
        else Toast.makeText(context, context.getString(R.string.app_config_cannot_launch), Toast.LENGTH_SHORT).show()
    }
    val onForceStopApp: () -> Unit = {
        scope.launch {
            if (runSuCommand("am force-stop $packageName")) {
                Toast.makeText(context, context.getString(R.string.app_config_force_stopped), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.app_config_no_root), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onRestartApp: () -> Unit = {
        scope.launch {
            if (!runSuCommand("am force-stop $packageName")) {
                Toast.makeText(context, context.getString(R.string.app_config_no_root), Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 等进程真正退出再启动（force-stop 返回 ≠ 进程已死），上限 5s
            var waited = 0
            while (waited < 5000 && isAppRunning()) {
                delay(100)
                waited += 100
            }
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) context.startActivity(intent)
            else Toast.makeText(context, context.getString(R.string.app_config_cannot_launch), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                onBack = onBack,
                onLaunchApp = onLaunchApp,
                onForceStopApp = onForceStopApp,
                onRestartApp = onRestartApp,
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },

        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalCutoutPadding()
                    .padding(top = 16.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    AppConfigInner(
                        packageName = packageName,
                        appLabel = appLabel,
                        hookEnabled = hookEnabled,
                        onHookEnabledChange = { hookEnabled = it; repository.setAppHookEnabled(packageName, it) },
                        mediaMode = mediaMode,
                        onMediaModeChange = { mediaMode = it; repository.setAppMediaMode(packageName, it.name.lowercase()) },
                        photoUri = photoUri,
                        photoDisplayPath = photoDisplayPath,
                        onPhotoUriChange = {
                            if (it == null) clearMedia(MediaMode.PHOTO)
                            else { photoUri = it; repository.setAppPhotoUri(packageName, it) }
                        },
                        videoUri = videoUri,
                        videoDisplayPath = videoDisplayPath,
                        onVideoUriChange = {
                            if (it == null) clearMedia(MediaMode.VIDEO)
                            else { videoUri = it; repository.setAppVideoUri(packageName, it) }
                        },
                        pendingMediaMode = pendingMediaMode,
                        onPickMedia = { mode ->
                            pendingMediaMode = mode
                            pickMediaLauncher.launch(
                                PickVisualMediaRequest(
                                    when (mode) {
                                        MediaMode.PHOTO -> ActivityResultContracts.PickVisualMedia.ImageOnly
                                        MediaMode.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
                                    }
                                )
                            )
                        },
                    )
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AppConfigInner(
    packageName: String,
    appLabel: String,
    hookEnabled: Boolean,
    onHookEnabledChange: (Boolean) -> Unit,
    mediaMode: MediaMode,
    onMediaModeChange: (MediaMode) -> Unit,
    photoUri: String?,
    photoDisplayPath: String?,
    onPhotoUriChange: (String?) -> Unit,
    videoUri: String?,
    videoDisplayPath: String?,
    onVideoUriChange: (String?) -> Unit,
    pendingMediaMode: MediaMode?,
    onPickMedia: (MediaMode) -> Unit,
) {
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            cornerRadius = rememberConcentricCardRadius(),
            insideMargin = PaddingValues(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(packageName, iconSizePx) {
                    loadAppIcon(context, packageName, iconSizePx)
                }
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp).size(48.dp),
                        filterQuality = FilterQuality.High,
                    )
                } else {
                    Box(Modifier.padding(end = 12.dp).size(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appLabel,
                        color = colorScheme.onSurface,
                        fontSize = textStyles.main.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = packageName,
                        fontSize = textStyles.body2.fontSize,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            cornerRadius = rememberConcentricCardRadius(),
        ) {
            SwitchPreference(
                title = stringResource(R.string.app_config_enable_hook),
                checked = hookEnabled,
                onCheckedChange = onHookEnabledChange,
            )
        }

        CardSegment(
            isFirst = true,
            isLast = true,
            outerHorizontalPadding = 12.dp,
            outerBottomPadding = 12.dp,
        ) {
            val list = listOf(
                stringResource(R.string.app_config_photo),
                stringResource(R.string.app_config_video),
            )
            val modes = listOf(MediaMode.PHOTO, MediaMode.VIDEO)
            val selectedIndex = modes.indexOf(mediaMode)
            OverlayDropdownPreference(
                title = stringResource(R.string.app_config_profile),
                items = list,
                selectedIndex = if (selectedIndex == -1) 0 else selectedIndex,
            ) {
                onMediaModeChange(modes[it])
            }

            AnimatedVisibility(
                visible = mediaMode == MediaMode.PHOTO,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickMedia(MediaMode.PHOTO) }
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_config_select_photo), fontSize = textStyles.main.fontSize, fontWeight = FontWeight.Medium, color = colorScheme.onBackground)
                        Text(
                            photoDisplayPath ?: photoUri ?: stringResource(R.string.app_config_no_media),
                            fontSize = textStyles.body2.fontSize, color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (photoUri != null) {
                        IconButton(onClick = { onPhotoUriChange(null) }) {
                            Icon(MiuixIcons.Delete, contentDescription = null, tint = colorScheme.onSurfaceVariantActions)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = mediaMode == MediaMode.VIDEO,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickMedia(MediaMode.VIDEO) }
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_config_select_video), fontSize = textStyles.main.fontSize, fontWeight = FontWeight.Medium, color = colorScheme.onBackground)
                        Text(
                            videoDisplayPath ?: videoUri ?: stringResource(R.string.app_config_no_media),
                            fontSize = textStyles.body2.fontSize, color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (videoUri != null) {
                        IconButton(onClick = { onVideoUriChange(null) }) {
                            Icon(MiuixIcons.Delete, contentDescription = null, tint = colorScheme.onSurfaceVariantActions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onLaunchApp: () -> Unit,
    onForceStopApp: () -> Unit,
    onRestartApp: () -> Unit,
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_config_title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val layoutDirection = LocalLayoutDirection.current
                    Icon(
                        modifier = Modifier.graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        tint = colorScheme.onBackground,
                    )
                }
            },
            actions = {
                val showTopPopup = remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showTopPopup.value = true },
                    holdDownState = showTopPopup.value,
                ) {
                    Icon(
                        imageVector = MiuixIcons.MoreCircle,
                        tint = colorScheme.onSurface,
                        contentDescription = null,
                    )
                }
                OverlayListPopup(
                    show = showTopPopup.value,
                    // 库内 ListPopupContent 写死 16dp 圆角且无参数，外层 squircle 裁剪跟随系统圆角
                    popupModifier = Modifier.squircleClip(rememberConcentricCardRadius()),
                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { showTopPopup.value = false },
                    content = {
                        ListPopupColumn {
                            val items = listOf(
                                stringResource(R.string.app_config_launch_app),
                                stringResource(R.string.app_config_force_stop),
                                stringResource(R.string.app_config_restart),
                            )
                            items.forEachIndexed { index, text ->
                                DropdownImpl(
                                    text = text,
                                    isSelected = false,
                                    optionSize = items.size,
                                    index = index,
                                    onSelectedIndexChange = {
                                        when (index) {
                                            0 -> onLaunchApp()
                                            1 -> onForceStopApp()
                                            2 -> onRestartApp()
                                        }
                                        showTopPopup.value = false
                                    },
                                )
                            }
                        }
                    }
                )
            },
            scrollBehavior = scrollBehavior,
        )
    }
}
