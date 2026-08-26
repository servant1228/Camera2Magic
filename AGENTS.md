# Camera2 Magic

LSPosed / libxposed（API 102）的 Android 虚拟摄像头模块。单模块 `:app`（`com.android.application`，AGP 9 内置 Kotlin，源码全在 `src/main`），基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发。UI 用 Compose + Miuix；核心是一个 **预编译闭源 `libcamera3.so`**（源码不入库），Hook 引擎运行在**目标应用进程内**。

本文件是 agent 指南的入口。只在特定改动里才需要的约束拆了出去，**按需读、不自动加载**：[docs/ui-guidelines.md](docs/ui-guidelines.md)（改 `ui/` 下任何文件前）。其余长期约束都在本文件。能从文件名与签名读出的信息不复述，这里只写约束、根因与症状指纹。

## 工作规程

- 每次改动至少跑与变更匹配的 Gradle 任务：改 Kotlin 用 `./gradlew :app:compileDebugKotlin`（秒级）；验证打包 / 资源才 `./gradlew assembleDebug`（分钟级）。
- **`testDebugUnitTest` 当前是坏的**：[ModuleStatusTest](app/src/test/java/com/nothing/camera2magic/ui/screen/home/ModuleStatusTest.kt) 仍按旧双参签名调用 `moduleStatus(xposedActive, masterSwitchEnabled)` 并断言已删除的 `ModuleStatus.Disabled`，而生产实现是单参两态（见 [ModuleStatus.kt](app/src/main/java/com/nothing/camera2magic/ui/screen/home/ModuleStatus.kt)）——测试源集编译不过。改这块前先对齐两者，不要绕过或删除断言了事。
- 保留用户已有的未提交改动；不用破坏性 reset/checkout；不读取、不输出、不提交 `app/keystore.properties` 与任何 keystore 文件。
- 完成后先报告变更与验证结果。**除非用户在当前请求中明确授权，不执行 `git add`/`commit`/`push`**。
- Commit 用 `<type>: <中文摘要>` 或纯中文摘要（type 取 `feat`/`fix`/`docs`/`chore`，与现有历史一致），主题行 ≤ 72 字符、无句尾句号；body 只讲代码里看不出的根因与取舍，不逐文件复述 diff。**每次发布前必须先产生新提交**——versionCode = git 提交数，不提交则 CI 产物 versionCode 不递增。
- 所有源文件保持 UTF-8（无 BOM）。中文注释乱码（形如 `鐗堟湰`）= 文件被非 UTF-8 编码读写过，立即停止并恢复。

## 技术栈

Kotlin 2.4（AGP 9 内置，不加独立 kotlin 插件）+ kotlinx.serialization。UI：Compose + Miuix 0.9.3（`-ui/-squircle/-icons/-blur/-preference` + `-navigation3-ui`）+ navigation3 + material-icons-extended。播放：media3-exoplayer（自定义 DataSource）。Hook：libxposed `api:102` compileOnly + `service:102` implementation。其他：hiddenapibypass、accompanist-permissions、kotlinx-collections-immutable。

**依赖版本唯一真源 = [gradle/libs.versions.toml](gradle/libs.versions.toml)**，坐标/SDK 在 [app/build.gradle](app/build.gradle)；新增依赖一律进 catalog，不在 build.gradle 硬编码版本号（现存少数硬编码条目是历史遗留，触及时顺手迁入 catalog）。SDK：compileSdk 37 / minSdk 33 / targetSdk 36，jvmTarget 21（miuix-nav 的 inline API 要求）。

## 代码地图

分层靠包名：仓库根包放进程入口（宿主 UI 与 Xposed 入口共用一个包），`hook/` 是只跑在目标进程里的引擎，`viewmodel/` 是只跑在宿主进程里的配置层，`ui/`、`utils/` 两边通用。

```
app/src/main/java/com/nothing/camera2magic/
├── MagicHook.kt      # Xposed 入口（java_init.list 指向它）
├── GlobalState.kt    # Hook 进程内全局态（appContext/processName/activityCount）
├── MainActivity.kt   # 宿主 UI 入口：CompositionLocal 组合根 + 主题装载
├── hook/             # ★ 四个 Hooker + 渲染端 Camera3 + JNI 契约 NativeBridge
├── viewmodel/        # ConfigRepository（宿主侧唯一配置读写点）+ 手写 VM 工厂
├── ui/{navigation3,screen,theme,component,util}
└── utils/Dog.kt      # 日志单例
app/src/main/resources/META-INF/xposed/   # module.prop / java_init.list / native_init.list / scope.list
app/src/main/jniLibs/arm64-v8a/libcamera3.so   # 预编译闭源产物（需提交进仓库）
```

四个屏幕 ↔ Route 对应、各 Hooker 的职责都能从文件名读出。`scope.list` 为空是有意的（作用域由模块内 UI 动态管理，见下文），不要往里加应用。

## 架构

```
宿主进程                                目标应用进程（Hook 侧）
────────────────                        ────────────────────────
MainActivity → CompositionLocal         MagicHook.onPackageReady(isFirstPackage)
  └ ConfigRepository ── save() 同步 ──→   SourceManager.init(remotePrefs)
     本地 prefs + XposedService            └ refreshAndDispatch（首个 Activity start）
       remotePrefs("camera_magic_config")     → 四个 Hooker 装配（Camera1/2/ImageReader/WebRTC）
       prepareRemoteMedia(openRemoteFile)  → BlackHole 假 Surface 替换预览面
                                            → NativeBridge(JNI) ←→ libcamera3.so
                                            → Camera3(ExoPlayer/Canvas) 渲染所选媒体
```

两个世界只有两条通道：**XposedService 的 remote preferences**（组名 `camera_magic_config`，键值全量同步）和 **`openRemoteFile` 文件描述符**（媒体内容）。除此之外没有任何共享——宿主侧改的每个键都必须走 `ConfigRepository.save()`（本地 + 远程双写）才能到达 Hook 侧。

### 配置流

- `ConfigRepository.save()` 双写本地 prefs 与 remote prefs；XposedService 绑定成功后 `syncAllToRemote()` 把本地全部键整体重推一次（覆盖 LSPosed 重装 / 数据被清的场景）。
- Hook 侧 `SourceManager.refreshPrefs()` 是所有键的唯一读取点，按当前包名（`processName.substringBefore(":")`，剥掉 `:xxx` 子进程后缀）解析 per-app 键：`app_hook_<pkg>`（默认 true）+ `app_media_mode_<pkg>`（photo/video）+ `app_remote_photo/video_<pkg>`。无有效媒体时 `validMedia = null`，不替换画面。全局媒体键已移除，别再加回来。
- **新增配置键必须两侧同时加**：`SourceManager`（Hook 侧读取）与 `ConfigRepository`（宿主侧读写 + 远程同步），键名保持 snake_case。只加一侧 = 配置静默不生效，无任何报错。
- `refreshPrefs()` 整体包在 `catch (e: Exception) { /* Do Nothing */ }` 里——配置读取失败是静默的，调试时先怀疑这里而不是 Hook 本身。
- **门控语义**：`readyForHook = moduleEnabled && appHookEnabled`，仅此两项。`hook_enabled_packages` 只做记录与同步、**不参与拦截门控**；「关闭某应用」由 `app_hook_<pkg>` 开关表达，「关闭媒体」由不选媒体表达。`main_inject_menu` 目前只是宿主 UI 开关，Hook 侧没有实现，不要当作已生效功能引用。

### Hook 拦截纪律

四条铁律，违反的直接后果是目标应用崩溃（对用户不可接受）：

1. **入口先判 `SM.readyForHook`**，不满足直接 `chain.proceed()` 放行。
2. **一切可能抛异常的代码包 `runCatching`**，失败只记日志，绝不阻断原调用——Hook 侧异常就是宿主应用闪退。
3. **动态回调类用 `javaClass.safeHook { }` 去重**（`hookedClasses` 是 WeakHashMap 集合，随回调类回收）；静态类用 `classLoader.safeHook(类名)`，类不存在只记警告。
4. **结束路径必须清理**：`BlackHole.clear()`（内部同步移除原生渲染目标再 release Surface）+ `Camera3.stop()`。漏清理 = Surface/纹理泄漏，表现为目标应用相机越用越卡直到崩溃。

已知坑（改对应代码前必读）：

- **`onClosed` 要 `getDeclaredMethod` 失败后回退 `getMethod`**：部分回调类不重写 `onClosed`，直接 `getDeclaredMethod` 抛 `NoSuchMethodException`，关闭清理整个不执行。
- **Camera1 open 拦截器里主动清上一轮渲染状态**：换相机但旧实例未 release 时，第二拍会卡死/闪退。
- **`removeTarget` 必须把 BlackHole 映射回原 Surface** 再传给原实现（`getBlackHole ?: origin`）——传替换面会让原生引擎的目标表错乱。
- **`Camera2Hooker.FULL_REPLACE_PACKAGE`**（当前 `com.xinchuzu.driver`）是对特定打卡应用的硬编码特例：全 Surface 替换（含录制/处理输出面）。其他应用只替换 format 1/4（RGBA_8888/RGB_565 预览面），format 35（YUV_420_888）只记尺寸（`updateAlgorithmSize`）并把原 Surface 加进 `extraRenderTargets`。新增特例要三处同步：三个 createCaptureSession 变体 + addTarget。
- **ImageReader JPEG 缓存**：key = `file_size_mtime_WxH`，`invalidateCache()` 挂在 `SourceManager.refreshAndDispatch()` 上。质量二分区间 lo=85/hi=100（保画质不往下探），超 buffer capacity 才降到 q=95 步长 -5 至 50；EXIF 最后写入、写不下就放弃 EXIF 保完整图。同尺寸缩放时 `createScaledBitmap` 返回**同一实例**，不能对别名 bitmap 重复 recycle（历史 bug #4）。
- **WebRTC 手动旋转优先**：`manuallyRotate > 0` 时忽略 WebRTC 日志里的自动 rotation，改走 `applyManualRotationToNative()`。会话停止（"Stop Camera2 session"）触发全套清理。

### 渲染管线与手动旋转

- `Camera3` 的**全部状态在 companion object**（player/surface/cachedBitmap…），多个相机实例同时打开共享同一渲染端；`init()` 用 CAS 保证一次，`stop()` 复位。所有操作 post 到 `Camera3Extended` 的单例 HandlerThread，别在其他线程碰 player。
- 视频 `REPEAT_MODE_ALL` 循环播放，`playSound=false` 时 volume=0；图片模式 ~30fps `lockHardwareCanvas` 重绘循环。ExoPlayer 经 [MagicDataSource](app/src/main/java/com/nothing/camera2magic/hook/MagicDataSource.kt) 读 PFD（支持 seek；close 不关 PFD，所有权在 `releaseResources`）。
- **手动旋转的正确路径**：`SourceManager.rememberCameraBaseData()` 记录最近一次 base data → `applyManualRotationToNative()` 叠加手动角度后重发 `updateCameraBaseData`（sensorOri 影响预览角 + YUV 旋转，displayOri 影响 Camera1 宽高交换）。**改旋转逻辑不要只调 `NB.updateManualRotation`**——那条路只有 WebRTC 自动旋转在用，且会被手动值覆盖。`main_manually_rotate` 的实时生效靠 SharedPreferences listener 触发上述链路。
- **JNI 契约单点 = [NativeBridge.kt](app/src/main/java/com/nothing/camera2magic/hook/NativeBridge.kt)**：`.so` 闭源且源码不入库，新增 `external fun` 必须在本机维护 cpp 源码重新编译并更新 jniLibs，否则运行期 `UnsatisfiedLinkError`。[proguard-rules.pro](app/proguard-rules.pro) 的 native keep 与全量 keep 两条规则**禁止修改**——删掉前者 JNI 全挂，删掉后者 Xposed 入口类被 shrink 掉，模块加载即失效。

## 关键架构约束

**深色判定单点**：`ThemeConfig.resolveIsDark(systemDark)` 是 colorMode→isDark 的唯一实现（Theme.kt:35 消费），组合树内一律读 `LocalAppDarkMode.current`。**已知违例**：MainActivity.kt:299 给悬浮导航栏传的是 `isSystemInDarkTheme()`——用户强制深/浅色时该处不跟随，修 UI 时顺手收敛到单点，新增代码严禁直接 `isSystemInDarkTheme()`。主题枚举的用户可见名统一走 [ThemeLabels.kt](app/src/main/java/com/nothing/camera2magic/ui/theme/ThemeLabels.kt)，禁止各自 when 映射。

**主题状态双份是设计而非缺陷**：MainActivity 持有 `themeConfig` state，SettingsViewModel.uiState 里还有一份；UI 改主题经 `onThemeConfigChanged` 回调上抛到 MainActivity 统一持久化。`theme_predictive_back` 变化会 `recreateWithoutTransition()` 整个重建 Activity（预测性返回开关需要 manifest 层生效）。持久化有个类型坑：多数布尔/数值 theme 键以 **String** 存（`"true"`/`"1.0"`），唯独 `theme_predictive_back` 是 Boolean——读写都走 ConfigRepository 的属性就安全，别绕过它直接碰 prefs。

**ViewModel 无 DI 框架**：手写 [ViewModelFactory](app/src/main/java/com/nothing/camera2magic/viewmodel/ViewModelFactory.kt)（when 分支 new），经 `LocalViewModelFactory` 下发；`LocalConfigRepository` 等 CompositionLocal 默认值是 `error()`——新 composable 直接读 `.current` 就会在预览/测试里炸，这是故意的，缺 provider 是装配错误要修装配而不是给默认值。

**国际化**：英文 + 简体中文（zh-rCN），文案分别进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；日志英文，代码注释中文。

## 构建

```bash
./gradlew assembleDebug        # 常规验证：直接用 jniLibs 预编译 .so
./gradlew buildNative          # 仅本机存在 app/src/main/cpp/ 时可用（见下）
./gradlew :app:compileDebugKotlin   # 最快语法/类型检查
```

- **`hasNativeSource` 门控一切原生逻辑**：`app/src/main/cpp/CMakeLists.txt` 不存在（公开仓库克隆即如此）时，CMake 不配置、快速编译模式启用、**`buildNative` 任务根本不注册**——报「任务不存在」不是环境坏了，是没有源码。有源码时 `buildNative` 删 jniLibs/release/.cxx → 依赖 strip 任务把 stripped 产物拷回 jniLibs（`upToDateWhen false`，永远真跑）。
- **`cleanOldJniLibs` 必须排在 merge 任务之前**：buildNative 自己的 doFirst 删除时机太晚（发生在依赖任务之后），不前置删除的话 `mergeReleaseNativeLibs` 会先把**旧的** libcamera3.so 合进去——构建全绿但 APK 里是陈旧 .so，症状与「native 改动没生效」无法区分。这条依赖关系已在 build.gradle 显式声明，别动。
- ABI split 只产 arm64-v8a，输出名 `CAM2Magic-<version>-arm64-v8a.apk`（androidComponents 重命名）。
- release 签名优先级：CI secrets（`CAM2MAGIC_KEYSTORE_B64` + `CAM2MAGIC_KEYSTORE_PASSWORD`/`CAM2MAGIC_KEY_ALIAS`/`CAM2MAGIC_KEY_PASSWORD`）> `CAM2MAGIC_KEYSTORE` 路径 > 本地 `app/keystore.properties`；都没有则产出未签名 APK 并打警告。minify + shrinkResources 开着，见上文 proguard 约束。
- **配置缓存必须保持关闭**（gradle.properties 已注明）：versionCode 在配置阶段执行 `git rev-list --count HEAD`，开缓存后该值被固化、不再随提交递增。同理 CI checkout 必须 `fetch-depth: 0`。
- **CI 不跑 buildNative**（[build-release.yml](.github/workflows/build-release.yml)，push master / `v*` tag / 手动触发）：正式发布流程 = 本机 `buildNative` 更新 jniLibs 产物 → 提交推送 master → CI 出签名包。`.so` 是提交进仓库的构建产物，这点与常规直觉相反，是有意的（源码不入库）。
