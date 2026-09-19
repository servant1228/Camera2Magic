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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nothing.camera2magic.R
import com.nothing.camera2magic.ui.component.AdaptiveTopAppBar
import com.nothing.camera2magic.ui.component.BlurredBar
import com.nothing.camera2magic.ui.component.CardSegment
import com.nothing.camera2magic.ui.component.ListPopupDefaults
import com.nothing.camera2magic.ui.component.rememberBlurBackdrop
import com.nothing.camera2magic.ui.component.rememberConcentricCardRadius
import com.nothing.camera2magic.ui.theme.LocalThemeConfig
import com.nothing.camera2magic.ui.theme.StatusColors
import com.nothing.camera2magic.utils.MediaPathResolver
import com.nothing.camera2magic.utils.LensKeys
import com.nothing.camera2magic.utils.LensSlot
import com.nothing.camera2magic.utils.LocalAppIconResolver
import com.nothing.camera2magic.viewmodel.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import com.nothing.camera2magic.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class MediaMode { PHOTO, VIDEO, NETWORK }

private fun mediaModeOf(value: String): MediaMode =
    when (value) {
        "video" -> MediaMode.VIDEO
        "network" -> MediaMode.NETWORK
        else -> MediaMode.PHOTO
    }

/** 网络流只接受这些协议；rtmp 之类 ExoPlayer 没有对应模块，不要放进来。 */
private val StreamUrlSchemes = listOf("rtsp://", "rtsps://", "http://", "https://")

private fun isSupportedStreamUrl(url: String): Boolean =
    StreamUrlSchemes.any { url.startsWith(it, ignoreCase = true) }

/** 一份媒体配置的位置：哪个镜头槽、哪种媒体模式。 */
private data class MediaTarget(val slot: LensSlot, val mode: MediaMode)

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
    // 旧的「不分镜头」配置先展开成前置/后置两份，此后本页只读写槽位键（否则删不干净，见 migrateLensMediaIfNeeded）
    remember(packageName) { repository.migrateLensMediaIfNeeded(packageName) }

    // 切换槽位时以 mediaSlot 为 remember key 整体重建下列状态：写操作都是即时落库的，不会丢改动
    var mediaSlot by remember { mutableStateOf(LensSlot.BACK) }
    var mediaMode by remember(mediaSlot) {
        mutableStateOf(mediaModeOf(repository.getAppMediaMode(mediaSlot, packageName)))
    }
    var photoUri by remember(mediaSlot) { mutableStateOf(repository.getAppPhotoUri(mediaSlot, packageName)) }
    var videoUri by remember(mediaSlot) { mutableStateOf(repository.getAppVideoUri(mediaSlot, packageName)) }
    var streamUrl by remember(mediaSlot) { mutableStateOf(repository.getAppStreamUrl(mediaSlot, packageName)) }
    var photoDisplayPath by remember(mediaSlot) { mutableStateOf<String?>(null) }
    var videoDisplayPath by remember(mediaSlot) { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaSlot, photoUri) {
        photoDisplayPath = photoUri?.let { uriString ->
            withContext(Dispatchers.IO) {
                MediaPathResolver.resolveDisplayPath(context, uriString.toUri())
            }
        }
    }
    LaunchedEffect(mediaSlot, videoUri) {
        videoDisplayPath = videoUri?.let { uriString ->
            withContext(Dispatchers.IO) {
                MediaPathResolver.resolveDisplayPath(context, uriString.toUri())
            }
        }
    }

    var pendingTarget by remember { mutableStateOf<MediaTarget?>(null) }
    // 每个「槽位+模式」一份在途拷贝：在一槽上选媒体绝不能掉掉另一槽正在跑的拷贝
    // （以前是一个全局 copyJob，两槽之后「边传大视频边去设另一槽」会变成常态动作）
    var copyJobs by remember { mutableStateOf<Map<MediaTarget, Job>>(emptyMap()) }

    fun copyToRemote(uri: Uri, target: MediaTarget) {
        copyJobs[target]?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            // 这就是 launch 返回的那个 Job，用来判定「我还是不是这个 target 的当前任务」
            val self = coroutineContext[Job]
            val mimeType = context.contentResolver.getType(uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            val base = LensKeys.remoteFileBase(target.slot, target.mode.name.lowercase(), packageName)
            val fileName = if (extension != null) "$base.$extension" else base
            val success = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    repository.prepareRemoteMedia(fileName, input)
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                // 未被同 target 的新任务取代才动状态，否则会把后者刚写好的键抹掉
                val current = copyJobs[target] === self
                if (current) copyJobs = copyJobs - target
                if (success) {
                    when (target.mode) {
                        MediaMode.PHOTO -> repository.setAppRemotePhoto(target.slot, packageName, fileName)
                        MediaMode.VIDEO -> repository.setAppRemoteVideo(target.slot, packageName, fileName)
                        // 不可达：网络流不拷贝文件，URL 直接落键
                        MediaMode.NETWORK -> Unit
                    }
                } else if (current) {
                    when (target.mode) {
                        MediaMode.PHOTO -> {
                            repository.setAppPhotoUri(target.slot, packageName, null)
                            // 只有还在展示这一槽时才动本地态：本地态按 mediaSlot 重建，
                            // 写过头的对象已经是个孤儿，写了也看不到
                            if (target.slot == mediaSlot) photoUri = null
                        }
                        MediaMode.VIDEO -> {
                            repository.setAppVideoUri(target.slot, packageName, null)
                            if (target.slot == mediaSlot) videoUri = null
                        }
                        // 不可达：网络流没有远程文件要回滚
                        MediaMode.NETWORK -> Unit
                    }
                    Toast.makeText(context, context.getString(R.string.app_config_media_copy_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        copyJobs = copyJobs + (target to job)
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pendingTarget ?: return@rememberLauncherForActivityResult
        pendingTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        // 部分 ROM 的 Photo Picker URI 不可持久化授权，失败不致命：媒体已被拷贝转存，URI 仅本进程内使用
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (target.mode) {
            MediaMode.PHOTO -> {
                photoUri = uri.toString()
                repository.setAppPhotoUri(target.slot, packageName, uri.toString())
            }
            MediaMode.VIDEO -> {
                videoUri = uri.toString()
                repository.setAppVideoUri(target.slot, packageName, uri.toString())
            }
            // 不可达：网络流不走相册选择器
            MediaMode.NETWORK -> Unit
        }
        copyToRemote(uri, target)
    }

    // 清空某槽位某模式的本地 + 远端媒体状态；该目标的拷贝在途则先取消，避免完成回调把已删除的状态写回
    fun clearMedia(target: MediaTarget) {
        copyJobs[target]?.cancel()
        copyJobs = copyJobs - target
        when (target.mode) {
            MediaMode.PHOTO -> {
                repository.getAppRemotePhoto(target.slot, packageName)?.let { file ->
                    // 先改键再查引用：迁移过来的两槽可能指向同一个文件，还有一个槽在用就绝不能删
                    repository.setAppRemotePhoto(target.slot, packageName, null)
                    if (!repository.isRemoteMediaReferenced(file, packageName)) repository.deleteRemoteMedia(file)
                }
                repository.setAppPhotoUri(target.slot, packageName, null)
                photoUri = null
            }
            MediaMode.VIDEO -> {
                repository.getAppRemoteVideo(target.slot, packageName)?.let { file ->
                    repository.setAppRemoteVideo(target.slot, packageName, null)
                    if (!repository.isRemoteMediaReferenced(file, packageName)) repository.deleteRemoteMedia(file)
                }
                repository.setAppVideoUri(target.slot, packageName, null)
                videoUri = null
            }
            // 不可达：网络流没有远程文件要删，清空走 onStreamUrlChange(null)
            MediaMode.NETWORK -> Unit
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
                        mediaSlot = mediaSlot,
                        onMediaSlotChange = { mediaSlot = it },
                        mediaMode = mediaMode,
                        onMediaModeChange = { mediaMode = it; repository.setAppMediaMode(mediaSlot, packageName, it.name.lowercase()) },
                        photoUri = photoUri,
                        photoDisplayPath = photoDisplayPath,
                        onPhotoUriChange = {
                            if (it == null) clearMedia(MediaTarget(mediaSlot, MediaMode.PHOTO))
                            else { photoUri = it; repository.setAppPhotoUri(mediaSlot, packageName, it) }
                        },
                        videoUri = videoUri,
                        videoDisplayPath = videoDisplayPath,
                        onVideoUriChange = {
                            if (it == null) clearMedia(MediaTarget(mediaSlot, MediaMode.VIDEO))
                            else { videoUri = it; repository.setAppVideoUri(mediaSlot, packageName, it) }
                        },
                        streamUrl = streamUrl,
                        onStreamUrlChange = {
                            repository.setAppStreamUrl(mediaSlot, packageName, it)
                            streamUrl = it
                        },
                        pendingTarget = pendingTarget,
                        onPickMedia = { mode ->
                            pendingTarget = MediaTarget(mediaSlot, mode)
                            pickMediaLauncher.launch(
                                PickVisualMediaRequest(
                                    when (mode) {
                                        MediaMode.PHOTO -> ActivityResultContracts.PickVisualMedia.ImageOnly
                                        MediaMode.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
                                        // 不可达：网络流没有相册选择器；仅为 when 穷尽
                                        MediaMode.NETWORK -> ActivityResultContracts.PickVisualMedia.ImageOnly
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
    mediaSlot: LensSlot,
    onMediaSlotChange: (LensSlot) -> Unit,
    mediaMode: MediaMode,
    onMediaModeChange: (MediaMode) -> Unit,
    photoUri: String?,
    photoDisplayPath: String?,
    onPhotoUriChange: (String?) -> Unit,
    videoUri: String?,
    videoDisplayPath: String?,
    onVideoUriChange: (String?) -> Unit,
    streamUrl: String?,
    onStreamUrlChange: (String?) -> Unit,
    pendingTarget: MediaTarget?,
    onPickMedia: (MediaMode) -> Unit,
) {
    val resolver = LocalAppIconResolver.current
    val iconPack = LocalThemeConfig.current.iconPack
    val iconSizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    var showStreamDialog by remember { mutableStateOf(false) }
    var streamDraft by remember { mutableStateOf("") }
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
                val icon = remember(packageName, iconSizePx, iconPack) {
                    resolver.load(iconPack, packageName, iconSizePx)
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
                stringResource(R.string.app_config_network),
            )
            val modes = listOf(MediaMode.PHOTO, MediaMode.VIDEO, MediaMode.NETWORK)
            val selectedIndex = modes.indexOf(mediaMode)
            // 镜头槽位固定两个：后置多颗镜头在多数机型上被聚合成同一个逻辑相机，
            // 变焦时由 HAL 内部换镜头（不重开相机）——按物理镜头分槽在机型间语义不一致
            val lensItems = listOf(
                stringResource(R.string.app_config_lens_back),
                stringResource(R.string.app_config_lens_front),
            )
            val slots = listOf(LensSlot.BACK, LensSlot.FRONT)
            val slotIndex = slots.indexOf(mediaSlot)
            OverlayDropdownPreference(
                title = stringResource(R.string.app_config_lens),
                items = lensItems,
                selectedIndex = if (slotIndex == -1) 0 else slotIndex,
            ) {
                onMediaSlotChange(slots[it])
            }
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

            AnimatedVisibility(
                visible = mediaMode == MediaMode.NETWORK,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.app_config_stream_url),
                    summary = streamUrl ?: stringResource(R.string.app_config_no_media),
                    onClick = {
                        streamDraft = streamUrl ?: ""
                        showStreamDialog = true
                    },
                    holdDownState = showStreamDialog,
                    endActions = {
                        if (streamUrl != null) {
                            IconButton(onClick = { onStreamUrlChange(null) }) {
                                Icon(MiuixIcons.Delete, contentDescription = null, tint = colorScheme.onSurfaceVariantActions)
                            }
                        }
                    },
                )
            }
            StreamUrlDialog(
                show = showStreamDialog,
                url = streamDraft,
                onUrlChange = { streamDraft = it },
                onDismiss = { showStreamDialog = false },
                onConfirm = { url ->
                    onStreamUrlChange(url)
                    showStreamDialog = false
                },
            )
        }
    }
}

/**
 * 网络流地址输入弹窗。空地址不允许确认；协议不合法时禁用确认并给提示。
 * 按钮顺序与权重遵循 UI 规范（cancel | confirm）。
 */
@Composable
private fun StreamUrlDialog(
    show: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val trimmed = url.trim()
    val valid = isSupportedStreamUrl(trimmed)
    WindowDialog(
        show = show,
        title = stringResource(R.string.app_config_stream_url),
        summary = stringResource(R.string.app_config_stream_url_summary),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (url.isNotEmpty() && !valid) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_config_stream_url_invalid),
                fontSize = textStyles.body2.fontSize,
                color = StatusColors.danger,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                enabled = valid,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { onConfirm(trimmed) },
            )
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
    BlurredBar(backdrop = backdrop, blurActive = backdrop != null) {
        AdaptiveTopAppBar(
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
