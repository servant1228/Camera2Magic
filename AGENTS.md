# Camera2 Magic

LSPosed / libxposed（API 102）的 Android 虚拟摄像头模块。单模块 `:app`（`com.android.application`），基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发。UI 用 Compose + Miuix；核心是一个 **预编译闭源 `libcamera3.so`**（源码不入库），Hook 引擎运行在**目标应用进程内**。

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

**依赖版本唯一真源 = [gradle/libs.versions.toml](gradle/libs.versions.toml)**，坐标/SDK 在 [app/build.gradle](app/build.gradle)；新增依赖一律进 catalog，不在 build.gradle 硬编码版本号（libxposed 两条是历史遗留，触及时顺手迁入 catalog）。SDK：compileSdk 37 / minSdk 34 / targetSdk 36，jvmTarget 21（miuix-nav 的 inline API 要求）。

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
└── utils/{Dog,MediaPathResolver}.kt
app/src/test/java/.../ui/screen/home/ModuleStatusTest.kt   # 唯一单测：纯 JVM，2 个用例
app/src/main/resources/META-INF/xposed/   # module.prop / java_init.list / native_init.list / scope.list
app/src/main/jniLibs/arm64-v8a/libcamera3.so   # 预编译闭源产物（需提交进仓库）
```

`Route` 有 4 个成员，但 `Route.Main` 内部是 3 个 tab 页的 pager，所以实际是 6 个页面。各 Hooker 的职责能从文件名读出，但**装配点不在 `HookManager`**——那是只提供 `safeHook`/`hookedClasses` 的 mixin 接口，真正 new 四个 Hooker 的地方是 `MagicHook.onPackageReady`。

`scope.list` 为空是有意的（`module.prop` 里 `staticScope=false`，作用域由模块内 UI 动态管理），不要往里加应用。`module.prop` 里**没有 name/description/version**，所以 LSPosed 展示的是 APK 的 label 与 `AndroidManifest` 的 `android:description`——改文案要改 manifest 指向的字符串，不是 module.prop。

单测约束：只有 `testImplementation junit`，没有 Robolectric、没有 `testOptions.unitTests.returnDefaultValues`，**测试必须不碰 Android framework**。CI 不跑单测。

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

### 配置流

- `ConfigRepository.save()` 双写本地 prefs 与 remote prefs；XposedService 绑定成功后 `syncAllToRemote()` 把本地全部键整体重推一次（覆盖 LSPosed 重装 / 数据被清的场景）。**远程写在 `safeExecute` 里，service 未绑定时静默跳过、无日志**——此时是纯本地写，靠 `syncAllToRemote()` 补齐。
- **service listener 是进程级单例**（companion 持有、`listenerRegistered` 防重入）：Activity 重建会 new 新 ConfigRepository 实例，绝不能把 listener 注册改回 `init` 里无守卫的实例级写法——那会随重建累积泄漏并重复触发全量同步。
- Hook 侧 `SourceManager.refreshPrefs()` 是所有键的唯一读取点，按当前包名（`processName.substringBefore(":")`，剥掉 `:xxx` 子进程后缀）解析 per-app 键：`app_hook_<pkg>`（默认 true）+ `app_media_mode_<pkg>`（photo/video）+ `app_remote_photo/video_<pkg>`。**包名解析失败时整段跳过、`appHookEnabled` 停留在默认 `true`，即 fail-open。** 无有效媒体时 `validMedia = null`，不替换画面。全局媒体键已移除，别再加回来。
- **新增配置键必须两侧同时加**：`SourceManager`（Hook 侧读取）与 `ConfigRepository`（宿主侧读写 + 远程同步），键名保持 snake_case。只加一侧 = 配置静默不生效，无任何报错。
- `refreshPrefs()` 整体包在 `catch (e: Exception) { /* Do Nothing */ }` 里，且 `prefs` 未初始化时静默 return——配置读取失败是静默的，调试时先怀疑这里而不是 Hook 本身。
- `refreshAndDispatch()` 在 `GlobalState.activityCount` 每次 `0→1` 时触发，也就是**每次目标应用回到前台都刷一遍**（不是只有首个 Activity）。同一路径上会按 `main_show_toast`（默认 true）弹 `"[✨] " + SM.toastMessage` 的 Toast，这是唯一的用户可见 Hook 反馈通道。
- **门控语义**：`readyForHook = appHookEnabled`（仅 `app_hook_<pkg>` 一项）。总开关 `main_module_enabled` 已删除，模块级启停只在 LSPosed 里做。`hook_enabled_packages` 只做记录与同步、**不参与拦截门控**；「关闭某应用」由 `app_hook_<pkg>` 表达，「关闭媒体」由不选媒体表达。
- **两个死开关**（UI 可见但 Hook 侧从不读，别当已生效功能引用）：`main_inject_menu`、`main_hook_mode`（首页那个 Camera1/2/3 选择器——四个 Hooker 在 `onPackageReady` 里是无条件全装的）。
- **宿主专用键**：`app_photo_uri_<pkg>` / `app_video_uri_<pkg>` 只供 UI 展示原始 URI，Hook 侧读的是 `app_remote_*`。另外全部 11 个 `theme_*` 键也会被全量推到远程组里（`save`/`syncAllToRemote` 不按键过滤），Hook 侧忽略。
- **持久化类型陷阱**：多数布尔/数值键以 **String** 存（`"true"`/`"1.0"`），非 String 的两个例外是 `theme_dark_mode`（**Int**）与 `theme_predictive_back`（**Boolean**）。读写都走 ConfigRepository 的属性就安全，别绕过它直接碰 prefs。唯一合法的例外是 `MainActivity.onCreate` 直读 `theme_predictive_back`——它必须早于 Compose 执行。
- `main_manually_rotate` 的实时生效靠 Hook 侧 `SourceManager.registerRotationListener()`（只筛这一个键 → `refreshPrefs()` + `applyManualRotationToNative()`）。listener 必须用字段强引用持住，SharedPreferences 只弱引用它。
- **备份会造成两侧失同步**：manifest 里 `allowBackup=true`，本地 prefs 可被系统备份、远程 prefs 不行；恢复后要到下一次 `syncAllToRemote()` 才对齐。

### Hook 拦截纪律

四条铁律，违反的直接后果是目标应用崩溃（对用户不可接受）：

1. **装配/替换路径入口先判 `SM.readyForHook`**，不满足直接 `chain.proceed()` 放行。**清理路径反过来：绝不判门控**——会话打开时门控为开、关闭时用户把它关掉，判门控会整段跳过清理并永久泄漏。这条区分是硬性的，`Camera1.release/stopPreview`、`Camera2.onClosed`、WebRTC 的 `"Stop Camera2 session"` 三处都按「无条件清理」写。
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
- **ImageReader JPEG 缓存**：单条缓存，key = `file_size_mtime_W_H`（`fstat` 失败退化成 `file_null_null_W_H`，同名换图会误命中；交替分辨率会反复失效），`invalidateCache()` 挂在 `SourceManager.refreshAndDispatch()` 上。质量二分区间 lo=85/hi=100（保画质不往下探），目标是 `max(64KB, min(原图, buffer容量) - 16KB)`（16KB 留给 EXIF）；超 buffer capacity 才降到 q=95 步长 -5 至 50；EXIF 最后写入、写不下就放弃 EXIF 保完整图。同尺寸缩放时 `createScaledBitmap` 返回**同一实例**，不能对别名 bitmap 重复 recycle（历史 bug #4）。
- **ImageReader 有 `sun.misc.Unsafe` 兜底写入路径**：正常 `buffer.put` 失败时，反射取 `java.nio.Buffer.address` + `sun.misc.Unsafe.putByte` 逐字节写几 MB。灰/黑名单 API + O(n) 反射循环，是最后一道保险，别当常规路径。临时文件是固定路径 `cacheDir/cam2magic_tmp.jpg`、无并发保护，两个线程同时拍会互相踩。
- **YUV 覆写两条路径都要判 `validMedia`**：`ImageReaderHooker` 的 format 35 与 `Camera1Hooker.onPreviewFrame` 都会调 `NB.overwriteYuvBuffer`。无媒体时原生引擎没有帧源，覆写等于把 App 的分析面写成黑帧——所以两处都在 `readyForHook` 之外再判一次 `validMedia != null`。
- **WebRTC 手动旋转优先**：`manuallyRotate > 0` 时忽略 WebRTC 日志里的自动 rotation，改走 `applyManualRotationToNative()`。两条路径的单位不同，必须用两个字段分别记（自动路径存角度，手动路径存索引 0..3）；混用一个字段时陈旧的自动角度会误判为「手动索引未变化」而抑制重新下发。
- **WebRTC 的 hook 目标是日志函数** `org.webrtc.Logging.nativeLog`：目标 App 每条日志都流经这个拦截器（所以里面必须先短路 `readyForHook` 再做字符串扫描），而且 App 关掉 WebRTC 日志或混淆掉该类，整个功能静默失效，只有一条 `safeHook` 警告。
- **除 `hookedClasses` 外的共享集合都不是线程安全的**：`BlackHole._oab`、`camera3Map` 都是裸 `WeakHashMap`，可从任意相机/binder 线程触达。`extraRenderTargets` 已改成 `synchronizedSet` 且遍历包在 `synchronized(...)` 里（它会在拦截器内被 `forEach`，裸集合直接 CME）；另外两个还没动，扩散写入前先想清楚。
- **`BlackHole` 里有两处会误导重构的写法**：`private val Surface.isValid get() = this.isValid` 与 `originSurfaces` 依赖「成员优先于扩展」的解析规则才不是无限递归——一旦有人「修正」它或 AOSP 改了成员名，立刻 StackOverflow。`dummyTexId` 从 `0x100` 起只增不减、只在 `clear()` 复位。

### 渲染管线与手动旋转

- `Camera3` 的**主要状态在 companion object**（player/surface/cachedBitmap/pfd/initialized…），多个相机实例同时打开共享同一渲染端；`init()` 用 CAS 保证一次，`stop()` 复位 `initialized`。但 **`imageRenderRunnable` 是实例级**：`stop()` 里的 `removeCallbacks` 只能撤掉本实例 post 的循环，而 WebRTCHooker 用的是 `Camera3().stop()`（新对象），所以那条路是空操作，图片循环只能靠下一 tick 自检 `imageRendering`/`initialized` 退出。`stop()` 也不置空 `player`（保留了一个已 release 的引用），`pause()`/`seekTo()` 没有 `initialized` 守卫。
- 所有操作 post 到 `Camera3Extended` 的单例 HandlerThread（线程名 `"Camera3"`，triage 时有用），别在其他线程碰 player。`Camera3Extended.release()` 从来没有调用者，线程活到进程结束；且 `Camera3` 在类初始化时就把 handler 抓进 `val`，这**绕过了** `Camera3Extended` 自己的「线程死了就重启」逻辑。
- `start()` 直接给 companion 的 `pfd` 赋值且不关旧的：两个相机先后打开时第二次 `init()` 会被 CAS 挡掉、但 `start()` 仍会覆写 `pfd` → FD 泄漏 + 对共享 player 二次 `setMediaSource`。
- 视频 `REPEAT_MODE_ALL` 循环播放，`playSound=false` 时 volume=0；图片模式用 `lockHardwareCanvas` 重绘循环，间隔是裸字面量 `33L`（≈30fps，没有命名常量），解码降采样到硬编码的 1080×1920 预算。绘制整体包在 `runCatching` 里且**不记日志**，掉帧是静默的。ExoPlayer 经 [MagicDataSource](app/src/main/java/com/nothing/camera2magic/hook/MagicDataSource.kt) 读 PFD（支持 seek；`close` 不关 PFD，所有权在 `releaseResources`；每次 `open` 都会对**共享** fd 重新 lseek，并发读会互相踩位置）。
- **手动旋转的正确路径**：`SourceManager.rememberCameraBaseData()` 记录最近一次 base data → `applyManualRotationToNative()` 重发 `updateCameraBaseData`。注意两个字段处理方式不同：`sensorOri` 是**叠加**（`(base + manual) % 360`，影响预览角 + YUV 旋转），`displayOri` 是**整体替换**成手动角度（影响 Camera1 宽高交换）。`main_manually_rotate` 存的是**索引 0..3** 不是角度。`applyManualRotationToNative()` 开头 `if (!baseDataSet) return`，所以 `Camera3` 里那两个调用点在没有任何相机 open 过时是静默空操作。**改旋转逻辑不要只调 `NB.updateManualRotation`**——那条路只有 WebRTC 自动旋转在用，且会被手动值覆盖。
- **JNI 契约单点 = [NativeBridge.kt](app/src/main/java/com/nothing/camera2magic/hook/NativeBridge.kt)，而且是双向的**：`.so` 闭源且源码不入库，新增 `external fun` 必须在本机维护 cpp 源码重新编译并更新 jniLibs，否则运行期 `UnsatisfiedLinkError`。**更危险的是反方向**——`ensureBuffer` / `frameUpdated` / `currentCamera` / `previewCallback` 在 Kotlin 侧没有任何调用者，它们纯粹是 native 的上行回调目标与可写字段；改名或被 shrink 掉**不会报错**，只是功能静默消失（`frameUpdated` 在这两个弱引用字段为空时直接 return）。[proguard-rules.pro](app/proguard-rules.pro) 只有两条规则且**禁止修改**：`-keepclasseswithmembernames class * { native <methods>; }` 保住 native 方法名，`-keep class com.nothing.camera2magic.** { *; }` 保住本模块全部类与成员（含 `MagicHook` 入口与上述上行字段）。注意后者只覆盖**本模块自己的包**，库代码照常被 shrink/混淆。

## 关键架构约束

**UI / 主题 / 组合根的全部约束在 [docs/ui-guidelines.md](docs/ui-guidelines.md)**，包括深色判定单点、主题状态双份的设计意图、CompositionLocal 与手写 ViewModel 工厂的装配纪律。改 `ui/` 或 `MainActivity` 的组合树之前读它。

**国际化**：英文 + 简体中文（zh-rCN），文案分别进 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；日志英文，代码注释中文。

**日志的真实门控与 `main_enable_log` 不一致**：`Dog.enabled` 只在**宿主进程**被赋值（两个 ViewModel 里），Hook 进程从不设置它。所以 Hook 侧凡是显式传 `true` 的调用点会无视开关照常输出，而省略该参数的调用点（如 `SourceManager` 里那条 `hookEnabledPackages` 日志）在目标进程里永远打不出来。排查「开关关了还在打日志」先看这里。日志 tag 是 `VCX`，`adb logcat -s VCX:*`。

**root / su 路径**（应用配置页的强停与重启）：`Runtime.exec(arrayOf("su","-c",cmd))` 必须留在 IO 线程、必须判 exit code、必须消费两个流；无 root 要给明确提示而不是假装成功。「应用是否在运行」是扫 `/proc/<pid>/cmdline` 而非 `ps`，重启前有 5s 有界等待。媒体拷贝契约：远程文件名 `<photo|video>_<pkg>.<ext>`（由 MIME 推导），写入前 `channel.truncate(0)`，失败要同时回滚 URI 状态与提示，清除媒体前要先取消在途拷贝。

**权限面很窄**：唯一权限是 `QUERY_ALL_PACKAGES`（作用域列表要用）。**没有任何存储权限**，也没有 `requestLegacyExternalStorage`——媒体只从 Photo Picker 来，再经 `openRemoteFile` 拷进模块私有目录。别为了「读文件」去加存储权限。manifest 里也**没有** Xposed 的 legacy `meta-data`（API 102 用 `META-INF/xposed/*`），别去「补」。预测性返回同样**不在 manifest**：是 `HiddenApiBypass` + 反射 `ApplicationInfo.setEnableOnBackInvokedCallback`，只能在 `onCreate` 生效，这才是切换该开关必须重建 Activity 的原因。

## 构建

```powershell
.\gradlew.bat assembleDebug            # 常规验证：直接用 jniLibs 预编译 .so
.\gradlew.bat :app:compileDebugKotlin  # 最快语法/类型检查
.\gradlew.bat :app:testDebugUnitTest   # 唯一单测
.\gradlew.bat buildNative              # 仅本机存在 app/src/main/cpp/ 时可用（见下）
```

- **`hasNativeSource` 门控一切原生逻辑**：`app/src/main/cpp/CMakeLists.txt` 不存在（`.gitignore` 忽略了整个 cpp 目录，公开仓库克隆即如此）时，CMake 不配置、快速编译模式启用、**`buildNative` 任务根本不注册**——报「任务不存在」不是环境坏了，是没有源码。有源码时 `buildNative` 删 jniLibs/release/.cxx → 依赖 strip 任务把 stripped 产物拷回 jniLibs（`upToDateWhen false`，永远真跑）。
- **`cleanOldJniLibs` 必须排在 merge 任务之前**：buildNative 自己的 doFirst 删除时机太晚（发生在依赖任务之后），不前置删除的话 `mergeReleaseNativeLibs` 会先把**旧的** libcamera3.so 合进去——构建全绿但 APK 里是陈旧 .so，症状与「native 改动没生效」无法区分。这条依赖关系已在 build.gradle 显式声明，别动。
- ABI split 只产 arm64-v8a，输出名 `CAM2Magic-<version>-arm64-v8a.apk`。**文件名是用 `majorVersion/minorVersion/patchVersion` 三个字面量重新拼的、不是读 `versionName`**，改版本号要同时确认这两处；变体没有 ABI filter 时文件名会变成 `...-null.apk`。versionName 靠手工 bump，release tag 也是手工打且必须对得上（只有 `v*` tag 触发 CI）。
- release 签名代码里的优先级是 `CAM2MAGIC_KEYSTORE_B64` > `CAM2MAGIC_KEYSTORE`（路径）> 本地 `app/keystore.properties`；都没有则产出未签名 APK 并打警告。**但 CI 实际走的是路径分支**——workflow 先把 `CAM2MAGIC_KEYSTORE_B64` secret 解码成临时文件，再以 `CAM2MAGIC_KEYSTORE` 喂给 Gradle，所以 build.gradle 里那个 B64 分支目前是死代码（留给本地/其他 CI）。minify + shrinkResources 开着，见上文 proguard 约束。
- **配置缓存不能开**：versionCode 在配置阶段执行 `git rev-list --count HEAD`，开缓存后该值被固化、不再随提交递增。gradle.properties 里只有一条**注释**说明，**并没有 `org.gradle.configuration-cache=false`**——所以一个 IDE 设置或误加的 `--configuration-cache` 就能静默冻结 versionCode。构建缓存（`org.gradle.caching=true`）是开着的。同理 CI checkout 必须 `fetch-depth: 0`。
- **CI 不跑 buildNative、也不跑单测**（[build-release.yml](.github/workflows/build-release.yml)，**仅 `v*` tag 与手动触发，push master 不触发**，唯一命令是 `./gradlew assembleRelease -x lintVitalRelease`）。正式发布流程 = 本机 `buildNative` 更新 jniLibs 产物 → 提交推送 master → 打 `v*` tag 推送（或手动 workflow_dispatch）→ CI 出签名包。CI **只上传 artifact、不创建 Release**。`.so` 是提交进仓库的构建产物，这点与常规直觉相反，是有意的（源码不入库）。
- Gradle wrapper 的 `distributionUrl` 指向**腾讯云镜像**，不是 services.gradle.org——CI 也从那里下载发行版。
