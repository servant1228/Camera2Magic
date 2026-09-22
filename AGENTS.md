# Camera2 Magic

LSPosed / libxposed（API 102）的 Android 虚拟摄像头模块。单模块 `:app`（`com.android.application`），基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发。UI 用 Compose + Miuix；核心是**自研原生渲染引擎 `libcamera3.so`**（源码在 `app/src/main/cpp/`，见该目录 [README](app/src/main/cpp/README.md)），Hook 引擎运行在**目标应用进程内**。

本文件是 agent 指南的入口。只在特定改动里才需要的约束拆了出去，**按需读、不自动加载**：[docs/ui-guidelines.md](docs/ui-guidelines.md)（改 `ui/` 下任何文件前）。其余长期约束都在本文件。能从文件名与签名读出的信息不复述，这里只写约束、根因与症状指纹。

## 工作规程

- 构建命令用 **`.\gradlew.bat`**：worktree 里 `gradlew` 是 CRLF 行尾，且 Windows `PATHEXT` 没有无扩展名项，`./gradlew` 在 PowerShell 里直接抛「无法在管道中加载此文档」。CI（ubuntu）才用 `./gradlew`。
- 每次改动至少跑与变更匹配的任务：改 Kotlin 用 `.\gradlew.bat :app:compileDebugKotlin`（秒级）；改 `ModuleStatus`/纯函数顺手 `.\gradlew.bat :app:testDebugUnitTest`；验证打包 / 资源才 `.\gradlew.bat assembleDebug`（分钟级）。
- 保留用户已有的未提交改动；不用破坏性 reset/checkout；不读取、不输出、不提交 `app/keystore.properties` 与任何 keystore 文件。
- 完成后先报告变更与验证结果。**除非用户在当前请求中明确授权，不执行 `git add`/`commit`/`push`**。
- Commit 用 `<type>: <中文摘要>` 或纯中文摘要（type 取 `feat`/`fix`/`docs`/`chore`，与现有历史一致），主题行 ≤ 72 字符、无句尾句号；body 只讲代码里看不出的根因与取舍，不逐文件复述 diff。**每次发布前必须先产生新提交**——versionCode = git 提交数，不提交则 CI 产物 versionCode 不递增。
- 所有源文件保持 UTF-8（无 BOM）。中文注释乱码（形如 `鐗堟湰`）= 文件被非 UTF-8 编码读写过，立即停止并恢复。**注意区分真乱码与终端渲染**：PowerShell 5.1 控制台会把全角字符（如 `）`）显示成问号状字节，那是渲染问题不是文件损坏，动手前先按 UTF-8 严格解码验证一遍。
- **改动推翻了本文件或 ui-guidelines.md 里的某条约束时，必须在同一次提交里改文档**（消除了一处「已知违例」就删掉它，换了依赖/重命名了单点就同步）。指南写具体版本号与行号必然漂移：引用代码用文件名 + 符号名，版本号只指向 catalog。
- **`docs/` 有 gitignore 陷阱**：`.gitignore` 是 `/docs/*` + `!/docs/ui-guidelines.md`。新增任何 docs 文件都会被静默忽略（`git add` 无声失败），必须同时往 `.gitignore` 加白名单行，否则「同一次提交里改文档」这条规则本身会失效。

## 技术栈

Kotlin + kotlinx.serialization。**AGP 9 自带 Kotlin 编译器，不加 `org.jetbrains.kotlin.android`**；只额外加两个 Kotlin 编译器插件（`kotlin.plugin.compose`、`kotlin.plugin.serialization`），catalog 里的 `kotlin` 版本只钉这两个插件、不决定编译器版本。UI：Compose + Miuix（`-ui/-squircle/-icons/-blur/-preference` + `-nav`）+ `androidx.navigationevent` + material-icons-extended。**导航是 miuix-nav，不是 androidx navigation3**（`ui/navigation3/` 只是迁移后遗留的包名）。播放：media3-exoplayer（自定义 DataSource）。Hook：libxposed `api` compileOnly + `service` implementation。其他：hiddenapibypass。

**依赖版本唯一真源 = [gradle/libs.versions.toml](gradle/libs.versions.toml)**，坐标/SDK 在 [app/build.gradle](app/build.gradle)；新增依赖一律进 catalog，不在 build.gradle 硬编码版本号（libxposed 两条是历史遗留，触及时顺手迁入 catalog）。SDK：compileSdk 37 / minSdk 33 / targetSdk 36（minSdk 受 `miuix-blur` AAR 自身声明的 33 限制，不能再低），jvmTarget 21（miuix-nav 的 inline API 要求）。

## 代码地图

分层靠包名：仓库根包放进程入口（宿主 UI 与 Xposed 入口共用一个包），`hook/` 是只跑在目标进程里的引擎，`viewmodel/` 是只跑在宿主进程里的配置层，`ui/`、`utils/` 两边通用。

```
app/src/main/java/com/nothing/camera2magic/
├── MagicHook.kt      # Xposed 入口（java_init.list 指向它）+ Application.onCreate 拦截
├── GlobalState.kt    # Hook 进程内全局态（appContext/processName/activityCount）
├── MainActivity.kt   # 宿主 UI 入口：CompositionLocal 组合根 + 主题装载
├── hook/             # ★ 四个 Hooker + HookManager(safeHook) + BlackHole + Camera3 + NativeBridge
├── viewmodel/        # ConfigRepository（宿主侧唯一配置读写点）+ 手写 VM 工厂
├── ui/{navigation3,screen,theme,component,util}
└── utils/{Dog,MediaPathResolver,LensSlot,CameraInventory}.kt
app/src/test/java/.../ui/screen/home/ModuleStatusTest.kt      # 纯 JVM，2 个用例
app/src/test/java/.../hook/SourceManagerTest.kt              # 纯 JVM，镜头槽位媒体解析
app/src/main/resources/META-INF/xposed/   # module.prop / java_init.list / native_init.list / scope.list
app/src/main/cpp/                              # 自研原生引擎源码（引擎/JPEG 编码器/JNI 桥/局部 GL ABI 声明）
app/src/main/jniLibs/arm64-v8a/libcamera3.so   # buildNative 产物（需提交进仓库）
```

`Route` 有 5 个成员，但 `Route.Main` 内部是 3 个 tab 页的 pager，所以实际是 7 个页面。各 Hooker 的职责能从文件名读出，但**装配点不在 `HookManager`**——那是只提供 `safeHook`/`hookedClasses` 的 mixin 接口，真正 new 四个 Hooker 的地方是 `MagicHook.onPackageReady`。

`scope.list` 为空是有意的（`module.prop` 里 `staticScope=false`，作用域由模块内 UI 动态管理），不要往里加应用。`module.prop` 里**没有 name/description/version**，所以 LSPosed 展示的是 APK 的 label 与 `AndroidManifest` 的 `android:description`——改文案要改 manifest 指向的字符串，不是 module.prop。

单测约束：只有 `testImplementation junit`，没有 Robolectric、没有 `testOptions.unitTests.returnDefaultValues`，**测试必须不碰 Android framework**——需要 `SharedPreferences` 时用手写假实现（接口在 android.jar 里是完整的，只有 stub 方法体会抛「not mocked」，见 `SourceManagerTest`），并且全程不开 `Dog` 日志（`Dog` 在 enabled=false 时短路，否则撞上 `android.util.Log`）。同理 `SourceManager` 是 object 单例，用例之间状态会串，靠 `init()` + `activateSlot()` 显式重置。CI 不跑单测。

## 架构

```
宿主进程                                目标应用进程（Hook 侧）
────────────────                        ────────────────────────
MainActivity → CompositionLocal         MagicHook.onPackageReady(param)
  └ ConfigRepository ── save() 同步 ──→   ├ param.isFirstPackage 守卫
     本地 prefs + XposedService            ├ SourceManager.init(remotePrefs)
       remotePrefs("camera_magic_config")  ├ Application.onCreate 拦截 → appContext
       prepareRemoteMedia(openRemoteFile)  └ 四个 Hooker 装配（Camera1/2/ImageReader/WebRTC）
                                            → refreshAndDispatch（每次回前台）+ Toast
                                            → BlackHole 假 Surface 替换预览面
                                            → NativeBridge(JNI) ←→ libcamera3.so
                                            → Camera3(ExoPlayer/Canvas) 渲染所选媒体
```

两个世界只有两条通道：**XposedService 的 remote preferences**（组名 `camera_magic_config`，键值全量同步、不过滤）和 **`openRemoteFile` 文件描述符**（媒体内容）。除此之外没有任何共享——宿主侧改的每个键都必须走 `ConfigRepository.save()`（本地 + 远程双写）才能到达 Hook 侧。

**`.so` 被加载两次，两条路都必需**：`native_init.list` 里的 `camera3` 供 LSPosed 做原生初始化，`MagicHook` 的 `init { System.loadLibrary("camera3") }` 供 JNI 符号绑定。看着冗余，删掉任一条都有一半概率整模块失效。

**原生引擎（app/src/main/cpp/，细节见该目录 README）**：单例 + 独立渲染线程持 EGL/GLES3 上下文；Kotlin 的 `Camera3` 把所选媒体画进 `SurfaceTexture` 并每帧通知原生渲染，原生把同一帧画到全部已注册 `ANativeWindow`（预览），并按需把当前帧渲染成 NV21 供 YUV/JPEG 消费。反汇编确认与旧闭源库的兼容点：预览的 fix 矩阵作用在**顶点**上（`gl_Position = u_FixMatrix * a_Position`，用于旋转/镜像/中心裁剪），YUV 的 fix 矩阵作用在**纹理坐标**上（带 0.5 中心平移），两者不可互换；按应用的旋转豁免表（WhatsApp、微信小程序 `com.tencent.mm:a`、Telegram、Instagram）与 Camera1 的 `displayOri % 180 == 90` 目标朝向分支必须保留，这是多款实机 app 的唯一验证依据，没有测试数据前不要"优化"。**媒体方向对齐已在 `.so` 内自动完成**：`alignSensorOrientation()` 按 `sensorOri + 媒体宽高比 + 媒体自身旋转`（视频 `unappliedRotationDegrees` / 图片 EXIF，经 `updateFrameInfo` 第三参传入）把竖屏媒体转进横屏传感器布局，因此 UI 的「手动旋转」默认应为 0；用户把它设成非 0 是在叠加额外偏移（旧版靠手动旋转补偿竖屏媒体的 90°，升级后不改回去会双倍旋转）。修改 C++ 后必须 `buildNative` 重编并提交 jniLibs，否则 APK 里仍是旧 .so。

### 配置流

- `ConfigRepository.save()` 双写本地 prefs 与 remote prefs；XposedService 绑定成功后 `syncAllToRemote()` 把本地全部键整体重推一次（覆盖 LSPosed 重装 / 数据被清的场景）。**远程写在 `safeExecute` 里，service 未绑定时静默跳过、无日志**——此时是纯本地写，靠 `syncAllToRemote()` 补齐。
- **service listener 是进程级单例**（companion 持有、`listenerRegistered` 防重入）：Activity 重建会 new 新 ConfigRepository 实例，绝不能把 listener 注册改回 `init` 里无守卫的实例级写法——那会随重建累积泄漏并重复触发全量同步。
- Hook 侧 `SourceManager.refreshPrefs()` 是所有键的唯一读取点，按当前包名（`processName.substringBefore(":")`，剥掉 `:xxx` 子进程后缀）解析 per-app 键：`app_hook_<pkg>`（默认 true）+ 镜头槽位四件套 `app_media_mode_<slot>_<pkg>`（`photo`/`video`/`network`）/ `app_remote_photo_<slot>_<pkg>` / `app_remote_video_<slot>_<pkg>` / `app_stream_url_<slot>_<pkg>`（网络视频流地址，纯字符串、不落盘），`<slot>` 只有 `front`/`back` 两个值。每槽解析链：**本槽显式配置 → 不分镜头的旧键（仅未迁移时）**；**模式键与三个媒体键全都不存在才算「没配置」→ null**（不向另一槽借用，见下面的门控语义）。把模式键也算进「显式配置」是必需的：用户把某槽切成 `network` 却还没填地址时，若只判「三个媒体键全空」就会回退旧键、把用户删掉的旧媒体复活。从没进过应用配置页的老应用因此回退到旧键，行为与改动前完全一致。`validMedia` 恒等于**当前槽**（`activeSlot`）的媒体，所有旁路消费者只读 `validMedia`。包名解析失败时整段跳过、`appHookEnabled` 停留在默认 `true`，即 fail-open。无有效媒体时 `validMedia = null`，不替换画面。全局媒体键已移除，别再加回来。
- **新增配置键必须两侧同时加**：`SourceManager`（Hook 侧读取）与 `ConfigRepository`（宿主侧读写 + 远程同步），键名保持 snake_case。只加一侧 = 配置静默不生效，无任何报错。**镜头槽位的键名与远程文件名基名单点在 [utils/LensSlot.kt](app/src/main/java/com/nothing/camera2magic/utils/LensSlot.kt) 的 `LensKeys`**，两侧都不许再手拼字符串（否则必然漂移）。
- **媒体按镜头分槽（前置/后置各一份）**：每槽完全独立，**一槽没配就是没媒体→那一镜头输出真实画面，绝不借用另一槽的**（曾经的「借用」语义已推翻，原因见门控语义一条）。槽位判定只在装配路径上做——Camera2 在 `onOpened` 里把 `LENS_FACING` 记进 `slotMap`、`onConfigured` 下发媒体前再 `SM.activateSlot(slotMap[camera])`；Camera1 在 `open` 里算出 `facingFront` 就激活、`startPreview` 再补一次。**槽位激活必须同时发生在 `onOpened`/`open`（早于 `createCaptureSession` 的换面决策）与 `onConfigured`/`startPreview`（幂等，覆盖同一路相机上媒体刚被改过的场景）**：只靠 `onConfigured` 的话，「这一路没配媒体」会跟着上一路的媒体走、照样被换面。识别不到槽位（特征读不到）时**沿用当前槽**，不清空媒体；`activateSlot()` 是 `@Synchronized`——`activeSlot` 与 `validMedia` 是一对必须同步演进的字段，双摄并发（两次 openCamera → 两个相机线程上的 onConfigured）会交错出「管线跑 A 槽、YUV/JPEG 旁路读 B 槽」，@Volatile 保证不了跨字段原子。为什么只有两个槽、为什么不能一镜头一槽、为什么不做多槽见 `LensSlot` 的 KDoc；一句结论：后置三四颗在多数机型上被聚合成同一个逻辑相机 id，App 变焦时 HAL 内部换镜头、不重开相机，Hook 侧看不到。
- `refreshPrefs()` 整体包在 `catch (e: Exception) { /* Do Nothing */ }` 里，且 `prefs` 未初始化时静默 return——配置读取失败是静默的，调试时先怀疑这里而不是 Hook 本身。
- `refreshAndDispatch()` 在 `GlobalState.activityCount` 每次 `0→1` 时触发，也就是**每次目标应用回到前台都刷一遍**（不是只有首个 Activity）。同一路径上会按 `main_show_toast`（默认 true）弹 `"[✨] " + SM.toastMessage` 的 Toast，这是唯一的用户可见 Hook 反馈通道。
- **门控语义（两个旗子，别合并成一个）**：
  - `appEnabled = app_hook_<pkg>` —— **记账/清理类路径**用：`Camera2.onOpened`、`Camera1.open`/`setPreviewCallback*`/`takePicture`（只负责装钩子/记 base data/记 `slotMap`/更新 `activatedCamera`）、以及 `Camera2.removeTarget`（把 BlackHole 映射回原面）。
  - `readyForHook = appEnabled && validMedia != null` —— **一切会改变目标应用看到的内容的路径**用：换预览面、`onConfigured`/`startPreview` 下发与注册渲染目标、YUV/JPEG 覆写。
  拆开是这次修「没配媒体就黑屏」时定下来的：以前两者合一（只看 `app_hook_<pkg>`），于是**面被换走但没人往里画** = 黑屏/冻结帧，而 Camera1 的 `onPictureTaken` 会在无帧源时把照片写成空/坏 JPEG。收在 `readyForHook` 单点而不是八个替换点各加一个 `&&`，就是为了不出现「第 9 个漏网」。
  **代价：记账与装钩子的路径绝不能改回判 `readyForHook`**——媒体为 null 是常见态（用户就是没选东西），那时如果连 `onOpened`/`open` 一起跳过，`activatedCamera` 不更新、上一轮被换走的面与原生目标表就永远没人清理（就是铁律 1 要防的泄漏）。同理两个「只装钩子」的点也不能判 `readyForHook`：`previewCallbackInterceptor` 与 `takePictureHook`——没配媒体时设的回调若没被注上，之后配好媒体也不会再接（Camera1 的 YUV/拍照路静默失效）。
  总开关 `main_module_enabled` 已删除，模块级启停只在 LSPosed 里做。`hook_enabled_packages` 只做记录与同步、**不参与拦截门控**。
  **每槽独立、没配就露真容**：后置配了、前置没配 → 前置输出**真实画面**（不是后置那份）。曾试过「空槽借用另一槽」，已推翻：一是产品语义不对（用户期望每槽独立），二是它会让空槽的 `validMedia` 变成非 null、**直接把 `teardownRenderIfIdle()` 的拆台逻辑关到**，症状反而依旧。代价：关 `app_hook_<pkg>` 与「两槽全清」效果一致，**没留下「黑屏遮挡」这种能力**，要做只能新增一种显式媒体类型（如 `MagicType.BLACK`），不要把它坩进门控里。好处是没配媒体的镜头不建 BlackHole、不注册原生渲染目标、不碰 player：零泄露面、零被 hook 痕迹。
- **媒体清空那一刻要主动拆台**：`SourceManager.teardownRenderIfIdle()` 由 `refreshAndDispatch()` 与 `activateSlot()` 调，内容是 `Camera3().stop()` + `NB.clearTargets()`。原生目标表与渲染管线不会自己停，不拆的话「改配置/换镜头前那一拍」注册过的面会被原生继续画旧帧盖住——**而且相机应用常前后置共用同一个预览 Surface，所以症状是「前置没配媒体，却看到后置那份」而不是黑屏**。它是清理动作所以**不判 `readyForHook`**（铁律 1），幂等。**故意不 `BlackHole.clear()`**：那批 dummy 面可能还在被未重建的会话写，release 它们会把错误抛回目标应用；常规 `onClosed` / `Camera1.open` 切换路径会收。
- **透传的生效点是下一次 `createCaptureSession`**：旧会话的相机输出已经在建会话时被引到 dummy 面上，会话存续期间改不回来（真实帧落在 dummy 面里，而 Kotlin 侧没有任何路径能把它贴回 App 的原面）。所以「改了配置没变化」的头号嫌疑不是逻辑错，而是**目标应用还拿着旧会话 / 目标进程还跑着旧模块代码**（Xposed 在进程启动时加载模块 dex，更新模块包不会热替换已活着的进程）——先用应用配置页顶栏菜单的「强制停止 + 重新启动」把目标应用真重启一遍再说。
- **死开关（只剩一个）**：`main_inject_menu`（UI 可见但 Hook 侧从不读，别当已生效功能引用）。`main_hook_mode`（首页那个 Camera1/2/3 选择器）**已连同 `HookModeDialog` 与 `HomeViewModel.onHookModeChanged` 一并删除**——四个 Hooker 在 `onPackageReady` 里本来就是无条件全装的，那个选择器永远是假的；首页同一位置现在是「镜头」只读计数卡。别再把它加回来。
- **宿主专用键**：槽位版 `app_photo_uri_<slot>_<pkg>` / `app_video_uri_<slot>_<pkg>` 只供 UI 展示原始 URI，Hook 侧读的是 `app_remote_*`。另外全部 12 个 `theme_*` 键也会被全量推到远程组里（`save`/`syncAllToRemote` 不按键过滤），Hook 侧忽略。
- **旧的不分镜头媒体键是只读遗留**：`ConfigRepository` 里只剩 getter，供 `migrateLensMediaIfNeeded()`（应用配置页进入时跑一次，标记键 `app_lens_migrated_<pkg>`）把旧值展开成前置/后置两份**并清空旧键**；它用 `saveBatch()` 一次本地 edit + 一次远程 edit，不是逐键 `save()`（迁移有成组十几个键，逐个 save 会在页面组装期抖十几次 binder）。清旧键是语义必需而不是美化：留着它，用户删掉某槽的媒体后 Hook 侧的回退链会把旧画面「复活」。**Hook 侧的回退开关是 `app_lens_migrated_<pkg>` 这个标记、不是「旧键还在不在」**：本地清旧键时若 service 未绑定，远程写会被静默跳过，而 `syncAllToRemote()` 只补写不删除——陈 key 会永久留在远程组里，拿它做判定等于把用户删掉的媒体放回来。**别给旧键重新加 setter**——迁移后槽位键会遮蔽它，写了不生效。
- **持久化类型陷阱**：布尔/数值键的存储类型分两派——`main_play_sound`/`main_enable_log`/`main_show_toast`/`main_inject_menu`/`app_hook_<pkg>`/`app_lens_migrated_<pkg>`/`theme_predictive_back` 以 **Boolean** 存，`main_manually_rotate`/`theme_dark_mode` 以 **Int** 存；其余布尔/数值键（`theme_pure_black`/`theme_monet`/`theme_blur`/`theme_floating_bottom_bar`/`theme_density_scale` 等）以 **String** 存。读写都走 ConfigRepository 的属性就安全，别绕过它直接碰 prefs。唯一合法的例外是 `MainActivity.onCreate` 直读 `theme_predictive_back`——它必须早于 Compose 执行。
- `main_manually_rotate` 与 `main_play_sound` 的实时生效靠 Hook 侧 `SourceManager.registerPreferenceListener()`（按 key 分发：旋转 → `refreshPrefs()` + `applyManualRotationToNative()`；声音 → `refreshPrefs()` + `Camera3().setPlaySound()` 直接改 ExoPlayer 音量）。**音频不属于 `.so`**：`.so` 只拿 SurfaceTexture 的画面帧，媒体解封装/音频解码/播放都在 Kotlin 的 ExoPlayer，所以这类开关的实时生效也只能在 Kotlin 侧做。listener 必须用字段强引用持住，SharedPreferences 只弱引用它。
- **备份会造成两侧失同步**：manifest 里 `allowBackup=true`，本地 prefs 可被系统备份、远程 prefs 不行；恢复后要到下一次 `syncAllToRemote()` 才对齐。

### Hook 拦截纪律

四条铁律，违反的直接后果是目标应用崩溃（对用户不可接受）：

1. **替换路径入口先判 `SM.readyForHook`、记账路径只判 `SM.appEnabled`**（两者差在「当前镜头有没有可播媒体」，见门控语义），不满足直接 `chain.proceed()` 放行。**清理路径反过来：绝不判门控**——会话打开时门控为开、关闭时用户把它关掉，判门控会整段跳过清理并永久泄漏。这条区分是硬性的，`Camera1.release/stopPreview`、`Camera2.onClosed`、`Camera2.removeTarget`（映射回原面）、WebRTC 的 `"Stop Camera2 session"` 四处都按「无条件清理」写。
2. **一切可能抛异常的代码包 `runCatching`**，失败只记日志，绝不阻断原调用——Hook 侧异常就是宿主应用闪退。尤其是：`chain.args[n] as X` 强转（`setPreviewTexture(null)`/`setPreviewDisplay(null)` 都是合法调用）、`getCameraCharacteristics`（抛 `CameraAccessException`）、`GlobalState.appContext`（`lateinit`，`Application.onCreate` 之前访问即抛）、`OutputConfiguration.mSurfaces` 反射、`NB.getSurfaceInfo` 的 `IntArray` 解构。
3. **动态回调类用 `javaClass.safeHook { }` 去重**，静态类用 `classLoader.safeHook(类名)`（类不存在只记警告，别裸 `loadClass`——异常会抛回 `onPackageReady` 连带拖挂其余 Hooker）。两个已知语义坑：`hookedClasses` 是**每 Hooker 实例一份**、不是全局；`safeHook` **先把类标记为已 hook 再执行 block**，block 抛异常就永久停在半 hook 状态、不重试，只留一条 error 日志。
4. **结束路径必须清理**：`BlackHole.clear()`（内部同步移除原生渲染目标再 release Surface）+ `Camera3.stop()`，Camera2 还要额外清 `extraRenderTargets`。漏清理 = Surface/纹理泄漏，表现为目标应用相机越用越卡直到崩溃。

已知坑（改对应代码前必读）：

- **`onClosed` 要 `getDeclaredMethod` 失败后回退 `getMethod`**：部分回调类不重写 `onClosed`，直接 `getDeclaredMethod` 抛 `NoSuchMethodException`，关闭清理整个不执行。
- **Camera1 `open` 是后置拦截**：必须先 `chain.proceed()` 拿到 Camera 实例才能判门控与清上一轮渲染状态——这是该方法的固有形态，不是违反铁律1。清上一轮状态的原因：换相机但旧实例未 release 时，第二拍会卡死/闪退。
- **Camera1 的 `setParameters`/`setDisplayOrientation` 有意不判门控**：它们只把 `vSize`/`pSize`/`displayOri` 记进 companion，不替换任何东西；门控从关翻到开时这些值必须已经是最新的。
- **`removeTarget` 必须把 BlackHole 映射回原 Surface** 再传给原实现（`getBlackHole ?: origin`）——传替换面会让原生引擎的目标表错乱。同理 `removeTarget` 不需要 `fullReplaceOutputs` 分支，这个映射同时兜住两种模式。
- **`Camera2Hooker.FULL_REPLACE_PACKAGE`**（`private const String`，当前 `com.xinchuzu.driver`，非集合、非公开符号）是对特定打卡应用的硬编码特例：全 Surface 替换（含录制/处理输出面）。其他应用只替换 format 1/4（RGBA_8888/RGB_565 预览面），format 35（YUV_420_888）只记尺寸（`updateAlgorithmSize`）并把原 Surface 加进 `extraRenderTargets`。**新增特例要四处同步**：三个 createCaptureSession 变体 + `addTarget`。注意 `addTarget` 只做预览面替换、**不**登记 `extraRenderTargets`（该 Surface 在会话创建阶段已登记过）。
- **两个 session 变体依赖私有字段就地改写** `OutputConfiguration.mSurfaces`（`@SuppressLint("SoonBlockedPrivateApi")`），`List<Surface>` 变体则是新建 ArrayList 换参——两套机制不可互换。AOSP 改字段名就会失效，因此三处都包了 `runCatching` 只放行。hook 目标也是 impl 类 `android.hardware.camera2.impl.CameraDeviceImpl` 而非公开 API。
- **ImageReader JPEG 缓存**：单条缓存，key = `file_size_mtime_W_H`（`fstat` 失败退化成 `file_null_null_W_H`，同名换图会误命中；交替分辨率会反复失效），`invalidateCache()` 挂在 `SourceManager.refreshAndDispatch()` 上。质量二分区间 lo=85/hi=100（保画质不往下探），目标是 `max(64KB, min(原图, buffer容量) - 16KB)`（16KB 留给 EXIF）；超 buffer capacity 才降到 q=95 步长 -5 至 50；EXIF 最后写入、写不下就放弃 EXIF 保完整图。同尺寸缩放时 `createScaledBitmap` 返回**同一实例**，不能对别名 bitmap 重复 recycle（历史 bug #4）。写入 buffer 后必须恢复与原生帧等价的状态：**limit 保持 capacity、position 归零、未覆盖尾部零填充**——CameraX 按 `capacity()` 消费 JPEG 面（`rewind()` + `get(byte[capacity])`），把 limit 缩小到 jpeg.size 会 `BufferUnderflowException`（症状指纹：CameraX 应用提示拍照失败、而模块侧日志显示写入成功）。
- **ImageReader 有 `sun.misc.Unsafe` 兜底写入路径**：正常 `buffer.put` 失败时，反射取 `java.nio.Buffer.address` + `sun.misc.Unsafe.putByte` 逐字节写几 MB。灰/黑名单 API + O(n) 反射循环，是最后一道保险，别当常规路径。临时文件用 `nanoTime` 唯一文件名，两线程同时拍各写各的、`finally` 里自删。
- **镜头槽位只在装配路径上生效**：`SM.activateSlot()` 由 Camera2 `onConfigured` / Camera1 `startPreview` 在**下发媒体前**调，属于铁律1 管辖的替换路径（未 `readyForHook` 时整个方法早就放行了）。清理路径不判槽位。槽位判定失败（特征读不到）时 `activateSlot(null)` **沿用当前槽**，绝不因此清空 `validMedia`。**双摄同开（concurrent camera = 两次 openCamera 拿两个 CameraDevice）时两路画面仍然是同一个媒体**，取最后配置的那路——原生引擎是单帧源单目标表（`Camera3::getInstance` + 一个 OES 纹理 + 扁平目标列表），`updateCameraBaseData` 的旋转/镜像也是全局标量，这不会因分槽而改变，要拆两路必须重编 `.so`。
- **YUV 覆写两条路径都要判 `validMedia`**：`ImageReaderHooker` 的 format 35 与 `Camera1Hooker.onPreviewFrame` 都会调 `NB.overwriteYuvBuffer`。无媒体时原生引擎没有帧源，覆写等于把 App 的分析面写成黑帧。自「不选媒体=透传」之后 `readyForHook` 已经包含 `validMedia != null`，所以这两处是**双保险而不是唯一防线——别把它们当成冗余删掉**（将来再把媒体语义从门控里拆出去时，它们是唯一还拦得住的地方）。
- **WebRTC 手动旋转优先**：`manuallyRotate > 0` 时忽略 WebRTC 日志里的自动 rotation，改走 `applyManualRotationToNative()`。两条路径的单位不同，必须用两个字段分别记（自动路径存角度，手动路径存索引 0..3）；混用一个字段时陈旧的自动角度会误判为「手动索引未变化」而抑制重新下发。
- **WebRTC 的 hook 目标是日志函数** `org.webrtc.Logging.nativeLog`：目标 App 每条日志都流经这个拦截器（所以里面必须先短路 `readyForHook` 再做字符串扫描），而且 App 关掉 WebRTC 日志或混淆掉该类，整个功能静默失效，只有一条 `safeHook` 警告。
- **共享集合一律 `synchronizedMap`/`synchronizedSet`**：`BlackHole._oab`、两个 Hooker 的 `camera3Map` 均为 `Collections.synchronizedMap(WeakHashMap)`（无遍历点），`extraRenderTargets` 为 `synchronizedSet` 且遍历包在 `synchronized(...)` 里。新增共享集合照此办理；注意 synchronizedMap 只保证单次调用原子，**遍历或 getOrPut 必须自己加 `synchronized(map)`**（拦截器内 `forEach` 裸集合直接 CME）。
- **`BlackHole` 里有两处会误导重构的写法**：`private val Surface.isValid get() = this.isValid` 与 `originSurfaces` 依赖「成员优先于扩展」的解析规则才不是无限递归——一旦有人「修正」它或 AOSP 改了成员名，立刻 StackOverflow。`dummyTexId` 从 `0x100` 起只增不减、只在 `clear()` 复位。

### 渲染管线与手动旋转

- `Camera3` 的**主要状态在 companion object**（player/surface/cachedBitmap/pfd/initialized…），多个相机实例同时打开共享同一渲染端；`init()` 用 CAS 保证一次，但**整体包在 `runCatching` 里且失败时复位 `initialized`**（native `createOESTexture()` 与 `ExoPlayer.Builder.build()` 都会抛，而它跑在 `"Camera3"` 线程的 posted block 里，漏到 Looper 就是目标应用闪退；不复位就会永久停在半初始化态），`stop()` 复位 `initialized`。但 **`imageRenderRunnable` 是实例级**：`stop()` 里的 `removeCallbacks` 只能撤掉本实例 post 的循环，而 WebRTCHooker 用的是 `Camera3().stop()`（新对象），所以那条路是空操作，图片循环只能靠下一 tick 自检 `imageRendering`/`initialized` 退出。`stop()` 也不置空 `player`（保留了一个已 release 的引用），`pause()`/`seekTo()` 没有 `initialized` 守卫。
- 所有操作 post 到 `Camera3Extended` 的单例 HandlerThread（线程名 `"Camera3"`，triage 时有用），别在其他线程碰 player。`Camera3Extended.release()` 从来没有调用者，线程活到进程结束；且 `Camera3` 在类初始化时就把 handler 抓进 `val`，这**绕过了** `Camera3Extended` 自己的「线程死了就重启」逻辑。
- `start()` 先关旧 `pfd` 再赋新值：连续两次 start 之间没有 stop 的场景（双相机并发 open）不再泄漏 FD；DataSource 各自持有 `dup` 副本，关原始 fd 不打断在播。
- **`Camera3.start()` 换媒体时分两种路径**：同类型（图→图、视频→视频、流→流）在活管线上原地换源；**类型变了（前置图 / 后置视频这种搭配）必须先 `stop()` 再起**——`ExoPlayer` 与 `lockHardwareCanvas` 两种生产者不能混挂在同一个 Surface 上。实现上是 `stop()` 后把 `openMedia` 再 post 一次，靠同一 handler 的 FIFO 保证「拆完再起」（`stop()` 自己也是 post 的，不能在当次 block 里顺序执行）。`activeType` 只在真的拿到帧源后才记（本地媒体看 pfd，网络流看非空 URL），否则失败的下一次起仍是原地换源。**不要在 `start()` 里改成直接串流水**，那是混生产者的入口。
- **网络视频流（`MagicType.NETWORK_STREAM`）走 ExoPlayer 默认 HTTP/RTSP 工厂，不经过 `MagicDataSource`**：`ValidMedia.file` 里装的是 URL，`Camera3.handleNetworkStream()` 用 `setMediaItem(MediaItem.fromUri(url))`。所以它**没有 pfd**，`openMedia()` 里 `activeType` 由该分支自己记。RTSP 依赖 `media3-exoplayer-rtsp`、HLS(m3u8) 依赖 `media3-exoplayer-hls`、DASH(mpd) 依赖 `media3-exoplayer-dash`（`media3-exoplayer` 本体只有 progressive HTTP(S)，这几个模块都是 `DefaultMediaSourceFactory` 用 `Class.forName` 反射发现的——**删掉任一依赖 = 对应协议静默播不了，不报错**；好消息是 `media3-exoplayer` 的 AAR 自带 consumer proguard 规则，已 `-keepclasseswithmembers` 保住这四个 `*MediaSource$Factory`，release 开 minify 也不会被裁）。SmoothStreaming（`.ism`）**故意不加**（微软已停用的死格式）。加这些协议**不需要改 UI 校验**（HLS/DASH 都跑在 `http(s)://` 上，已有的 scheme 白名单自然放行）。**断流重试**在 `onPlayerError` 里做：2s 后重 prepare、最多 3 次，重试前必须校验「这条 URL 仍是当前 `validMedia`」——不校验的话用户切走媒体后旧流会诈尸。**起播门槛**用 `DefaultLoadControl.setBufferDurationsMsForStreaming(1500,15000,300,800)` 把默认的 1000ms 降到 300ms：弱网/高延迟下默认要缓够 1s 媒体才出第一帧，用户会当成黑屏（本地视频的 uri 是 `LOCAL://VIDEO`，不在 `LOCAL_PLAYBACK_SCHEMES` 里，同样吃这条配置，无副作用）。`onPlaybackStateChanged`/`onVideoSizeChanged`/`onPlayerError` 都有受门控的日志，排查「黑屏」先看 `player state:` 走到哪一步。**网络能力用的是目标应用的身份**：目标 App 必须有 `INTERNET` 权限，`http://` 明文流还受目标 App 自己的 `networkSecurityConfig` 限制（RTSP 不走 HTTP 明文策略）。这是约束不是 bug。
- **ImageReader 的 JPEG 替换（format 256）只对 `LOCAL_IMAGE` 生效**：视频 / 网络流没有「一张图」可解，且网络流的 file 是 URL，传给 `openRemoteFile` 会去开非法文件名，所以入口显式判类型直接透传（视频/流的替换帧由 native 引擎在 Camera1 拍照路径与 YUV 路径提供）。
- 视频 `REPEAT_MODE_ALL` 循环播放，`playSound=false` 时 volume=0；图片模式用 `lockHardwareCanvas` 重绘循环，间隔 `FRAME_INTERVAL_MS`=33ms（≈30fps）；解码按长边预算 `FRAME_LONG_EDGE` 收紧（4K 设备 3840，`isLowRamDevice` 降 1920），横竖对称、pow2 粒度最坏落到预算一半，小图绝不放大。绘制整体包在 `runCatching` 里且**不记日志**，掉帧是静默的。ExoPlayer 经 [MagicDataSource](app/src/main/java/com/nothing/camera2magic/hook/MagicDataSource.kt) 读 PFD（支持 seek；`open` 时 `ParcelFileDescriptor.dup` 私有副本、`close` 只关副本，原始 fd 所有权在 `releaseResources`；并发读各持各的偏移，不再互踩）。
- **手动旋转的正确路径**：`SourceManager.rememberCameraBaseData()` 记录最近一次 base data → `applyManualRotationToNative()` 重发 `updateCameraBaseData`。注意两个字段处理方式不同：`sensorOri` 是**叠加**（`(base + manual) % 360`，影响预览角 + YUV 旋转），`displayOri` 是**整体替换**成手动角度（影响 Camera1 宽高交换）。`main_manually_rotate` 存的是**索引 0..3** 不是角度。`applyManualRotationToNative()` 开头 `if (!baseDataSet) return`，所以 `Camera3` 里那两个调用点在没有任何相机 open 过时是静默空操作。**改旋转逻辑不要只调 `NB.updateManualRotation`**——那条路只有 WebRTC 自动旋转在用，且会被手动值覆盖。
- **JNI 契约单点 = [NativeBridge.kt](app/src/main/java/com/nothing/camera2magic/hook/NativeBridge.kt)，而且是双向的**：原生实现是仓库内的 `app/src/main/cpp/native_bridge.cpp`（`JNI_OnLoad` + `RegisterNatives` 的 `kNativeMethods` 表），修改任何 `external fun` 必须**两侧同改**，否则不是编译错误而是运行期 `UnsatisfiedLinkError`。[proguard-rules.pro](app/proguard-rules.pro) 只有两条规则且**禁止修改**：`-keepclasseswithmembernames class * { native <methods>; }` 保住 native 方法名，`-keep class com.nothing.camera2magic.** { *; }` 保住本模块全部类与成员（含 `MagicHook` 入口）。注意后者只覆盖**本模块自己的包**，库代码照常被 shrink/混淆。旧库那批 `ensureBuffer` / `frameUpdated` / `currentCamera` / `previewCallback` 上行回调字段已被删除：反汇编确认旧 `.so` 从未引用它们，是上游遗留死代码。

## 关键架构约束

**UI / 主题 / 组合根的全部约束在 [docs/ui-guidelines.md](docs/ui-guidelines.md)**，包括深色判定单点、主题状态双份的设计意图、CompositionLocal 与手写 ViewModel 工厂的装配纪律。改 `ui/` 或 `MainActivity` 的组合树之前读它。

**国际化**：英文 + 简体中文（zh-rCN），文案分别进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；日志英文，代码注释中文。

**日志门控单开关**：全部日志（**含异常路径**）统一受 `main_enable_log` 控制——`Dog.enabled` 只在**宿主进程**被赋值，唯一赋值点是 `ConfigRepository`（init 从 prefs 恢复 + `enableLog` setter 实时更新），Hook 进程从不设置它。Hook 侧调用点必须显式传 `SM.enableLog`（`HookManager`/`WebRTCHooker` 用 `SourceManager.enableLog`），**省略参数 = 默认 `Dog.enabled` = 在目标进程永远 false，日志沦为死代码**；宿主侧 `ConfigRepository` 的日志省略参数依赖 `Dog.enabled`，是唯一合法用法。开关关闭 = 两侧 logcat 零输出，这是有意的隐蔽取舍：异常同样拿不到归因线索，排查时先开开关再复现。新增调用点禁止传 `true`——那是旧「异常常开」策略的残留，出现即漏改。日志 tag 是 `VCX`，`adb logcat -s VCX:*`；Hook 侧受控日志由 `Dog` 双写到 `XposedModule.log`（`moduleSink` 由 `MagicHook.onPackageReady` 注入，宿主进程恒 null，异常 stack 一并写入），在 **LSPosed 管理器 → 日志 → 模块日志** 可见，是免 adb 的查看通道。

**root / su 路径**（应用配置页的强停与重启）：`Runtime.exec(arrayOf("su","-c",cmd))` 必须留在 IO 线程、必须判 exit code、必须消费两个流；无 root 要给明确提示而不是假装成功。「应用是否在运行」是扫 `/proc/<pid>/cmdline` 而非 `ps`，重启前有 5s 有界等待。媒体拷贝契约：远程文件名 `<photo|video>_<slot>_<pkg>.<ext>`（槽位段与基名走 `LensKeys.remoteFileBase`，扩展名由 MIME 推导），写入前 `channel.truncate(0)`，失败要同时回滚 URI 状态与提示，清除媒体前要先取消在途拷贝、**并且先改键再查 `isRemoteMediaReferenced()` 才能 `deleteRemoteMedia`**（迁移过来的两槽可能指向同一个旧文件名，直接删会拆掉另一槽）。

**权限面很窄**：唯一权限是 `QUERY_ALL_PACKAGES`（作用域列表要用）。**不加 `CAMERA`**：镜头枚举（`getCameraIdList`/`getCameraCharacteristics`）本来就不要权限，而 `LENS_FACING` 是否可读**由机型决定**（各厂商在 `getKeysNeedingPermission()` 里自行声明那批受限键，AOSP 不保证对无 CAMERA 权限的调用方开放）。所以 `CameraInventory.count()` 的口径是「前置 + 后置」（按 `LENS_FACING` 分类，即 App 真正会去打开的那几个），**任一 id 的朝向读不到就整体返回 null、UI 显示 `—`，绝不猜**；它**故意不做「几颗物理镜头」的推断**（多颗后置被聚合成同一个逻辑相机 id，id 数 ≠ 物理镜头数）。主页那张卡与 `DeviceInfoCard` 都读同一个函数。焦距级别的镜头识别一律留在 Hook 侧（那边用目标应用身份，必然有 CAMERA），不要为此给模块声明 CAMERA（对一个虚拟摄像头模块来说，那本身就是检测指纹）。**没有任何存储权限**，也没有 `requestLegacyExternalStorage`——媒体只从 Photo Picker 来，再经 `openRemoteFile` 拷进模块私有目录。别为了「读文件」去加存储权限。网络视频流同理**不给模块加 `INTERNET`**——流是在目标进程里拉的，用的是目标 App 的权限与网络策略，给模块加权限既没用又是多余指纹。manifest 里也**没有** Xposed 的 legacy `meta-data`（API 102 用 `META-INF/xposed/*`），别去「补」。预测性返回同样**不在 manifest**：是 `HiddenApiBypass` + 反射 `ApplicationInfo.setEnableOnBackInvokedCallback`，只能在 `onCreate` 生效，这才是切换该开关必须重建 Activity 的原因。

## 构建

```powershell
.\gradlew.bat assembleDebug            # 常规验证：直接用 jniLibs 里已提交的 .so（不重编 native）
.\gradlew.bat :app:compileDebugKotlin  # 最快语法/类型检查
.\gradlew.bat :app:testDebugUnitTest   # 两个纯 JVM 单测
.\gradlew.bat buildNative              # 重编 app/src/main/cpp/ → strip → 同步 jniLibs（发版前必跑）
```

- **`hasNativeSource` 门控一切原生逻辑**：原生源码已入库（`app/src/main/cpp/CMakeLists.txt` 必定存在），但
  `buildNative` 之外的构建仍走**快速编译模式**：CMake/Strip 任务被禁用、直接用 `jniLibs` 里已提交的 `.so`，
  所以改 C++ 后必须跑 `buildNative` 才会进 APK。有源码时 `buildNative` 删 jniLibs/release/.cxx → 依赖 strip
  任务把 stripped 产物拷回 jniLibs（`upToDateWhen false`，永远真跑）。Termux 上可用
  `app/src/main/cpp/build_local.sh` 做纯语法/链接自检（不产出行使 `buildNative` 的 strip 流程）。
- **`cleanOldJniLibs` 必须排在 merge 任务之前**：buildNative 自己的 doFirst 删除时机太晚（发生在依赖任务之后），不前置删除的话 `mergeReleaseNativeLibs` 会先把**旧的** libcamera3.so 合进去——构建全绿但 APK 里是陈旧 .so，症状与「native 改动没生效」无法区分。这条依赖关系已在 build.gradle 显式声明，别动。
- ABI split 只产 arm64-v8a，输出名 `CAM2Magic-<version>-arm64-v8a.apk`。**文件名是用 `majorVersion/minorVersion/patchVersion` 三个字面量重新拼的、不是读 `versionName`**，改版本号要同时确认这两处；变体没有 ABI filter 时文件名会变成 `...-null.apk`。versionName 靠手工 bump，release tag 也是手工打且必须对得上（只有 `v*` tag 触发 CI）。
- release 签名代码里的优先级是 `CAM2MAGIC_KEYSTORE_B64` > `CAM2MAGIC_KEYSTORE`（路径）> 本地 `app/keystore.properties`；都没有则产出未签名 APK 并打警告。**但 CI 实际走的是路径分支**——workflow 先把 `CAM2MAGIC_KEYSTORE_B64` secret 解码成临时文件，再以 `CAM2MAGIC_KEYSTORE` 喂给 Gradle，所以 build.gradle 里那个 B64 分支目前是死代码（留给本地/其他 CI）。minify + shrinkResources 开着，见上文 proguard 约束。
- **配置缓存不能开**：versionCode 在配置阶段执行 `git rev-list --count HEAD`，开缓存后该值被固化、不再随提交递增。gradle.properties 里只有一条**注释**说明，**并没有 `org.gradle.configuration-cache=false`**——所以一个 IDE 设置或误加的 `--configuration-cache` 就能静默冻结 versionCode。构建缓存（`org.gradle.caching=true`）是开着的。同理 CI checkout 必须 `fetch-depth: 0`。
- **CI 不跑 buildNative、也不跑单测**（[build-release.yml](.github/workflows/build-release.yml)，**仅 `v*` tag 与手动触发，push master 不触发**，唯一命令是 `./gradlew assembleRelease -x lintVitalRelease`）。正式发布流程 = 本机 `buildNative` 更新 jniLibs 产物 → 提交推送 master → 打 `v*` tag 推送（或手动 workflow_dispatch）→ CI 出签名包。CI **只上传 artifact、不创建 Release**。`.so` 是提交进仓库的构建产物（源码也在仓库里，但 CI 不重编），这点与常规直觉相反，是有意的。
- **本机 git 拿不到仓库时 versionCode 静默退化成 1**：`getGitCommitCount()` 把异常吞了 `return 1`，而 Windows 上仓库目录属主与当前账号不一致时 `git rev-list` 直接报 `detected dubious ownership` → **打出来的包 versionName 照常、versionCode = 1**，装机被当成降级拒绝，症状与「包坏了」无法区分。出正式包前先确认 `git rev-list --count HEAD` 能跑；不能跑就用**进程级**注入绕过（不碰用户全局配置）：`GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=<仓库路径> ./gradlew --no-daemon assembleRelease`——**必须带 `--no-daemon`**，否则 `git` 是在早已启动的 Gradle daemon 环境里执行，看不到客户端的变量。CI（ubuntu）无此问题。
- Gradle wrapper 的 `distributionUrl` 指向**腾讯云镜像**，不是 services.gradle.org——CI 也从那里下载发行版。
