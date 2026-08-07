# Camera2 Magic - AI 项目指南

> 本文件用于帮助 AI（Codex 等）快速理解 `Camera2Magic` 项目，并在修改代码时遵守开发规范。
> 适用读者：任何需要在仓库内阅读、修改、构建代码的开发者或 AI 代理。

## 1. 项目是什么

**Camera2 Magic（包名 `com.nothing.camera2magic`，产物名 CAM2Magic）** 是一个基于
**LSPosed / libxposed（API 102）** 的 **Android 虚拟摄像头模块**：

- 用户在宿主 App 中选择本地视频 / 图片作为“虚拟摄像头”内容；
- 当被 Hook 的应用（scope 当前只有 `tv.danmaku.bili` 哔哩哔哩）调用
  Camera1 / Camera2 / ImageReader / WebRTC 等路径请求摄像头时，
  模块用所选媒体替换真实画面；
- 宿主 App 与 Hook 进程通过 `XposedService` IPC 共享配置和媒体文件。

架构上它是 **“Xposed Hook 引擎 + Compose 宿主 UI”一体化单模块工程**，且带有一个
**预编译的 C++ 原生库 `libcamera3.so`**（源码不在本仓库内）。

## 2. 技术栈与关键版本

| 项目 | 版本 / 值 |
| --- | --- |
| 构建系统 | Gradle 9.5.0（腾讯云镜像分发），AGP 9.3.0 |
| 原生依赖 | libjpeg-turbo 3.1.3（vendored，`src/main/cpp/libjpeg-turbo/`，与原库版本一致） |
| Kotlin | 2.4.10（jvmTarget 11，官方代码风格） |
| SDK | compileSdk 37（release DSL），minSdk 33，targetSdk 36 |
| NDK | 29.0.14206865（CMake 默认关闭，见“构建规范”） |
| UI | Jetpack Compose + **Miuix 0.9.3**（HyperOS 风格组件库） |
| 导航 | androidx.navigation3 1.1.4（`Route` 为 @Serializable sealed 层级）+ `miuix-navigation3-ui`（随 Miuix 0.9.3） |
| 播放器 | media3-exoplayer 1.10.0（`ExoPlayer` + 自定义 `DataSource`） |
| Hook 框架 | libxposed：`compileOnly api:102.0.0` + `implementation service:102.0.0` |
| 序列化 | kotlinx.serialization-json 1.7.3（导航栈持久化） |
| 其他 | hiddenapibypass 6.1、accompanist-permissions、kotlinx-collections-immutable |

## 3. 目录结构

```text
Camera2Magic/
├── app/
│   ├── build.gradle                 # 版本、签名、ABI split、buildNative 任务
│   ├── cam2magic.keystore           # release 签名（密码 camera2，见构建规范）
│   ├── proguard-rules.pro           # keep native 方法与 com.nothing.camera2magic.**
│   └── src/main/
│       ├── AndroidManifest.xml      # 单 Activity，QUERY_ALL_PACKAGES + FileProvider
│       ├── java/com/nothing/camera2magic/
│       │   ├── MagicHook.kt         # Xposed 入口（java_init.list）
│       │   ├── GlobalState.kt       # 跨进程内存态（appContext/processName/activityCount）
│       │   ├── MainActivity.kt      # 宿主 UI 入口 + AppNavigation + 主题装载
│       │   ├── hook/                # ★ Hook 引擎（核心）
│       │   │   ├── Camera1Hooker.kt / Camera2Hooker.kt
│       │   │   ├── ImageReaderHooker.kt / WebRTCHooker.kt
│       │   │   ├── Camera3.kt / Camera3Extended.kt   # ExoPlayer 渲染端
│       │   │   ├── NativeBridge.kt  # JNI 桥（external fun 列表）
│       │   │   ├── BlackHole.kt / ShortId.kt         # 假 Surface 池 / 日志标识
│       │   │   ├── MagicDataSource.kt / MagicMedia.kt / SourceManager.kt
│       │   │   └── HookManager.kt  # safeHook 工具接口
│       │   ├── ui/                  # Compose UI（screen/component/navigation3/theme/util）
│       │   ├── utils/Dog.kt         # 日志单例（StateFlow + logcat 桥接监听）
│       │   ├── utils/MediaPathResolver.kt  # content:// 媒体解析为可展示路径
│       │   ├── view/SpotlightView.kt
│       │   └── viewmodel/           # ConfigRepository + 4 个 ViewModel + Factory + CompositionLocals
│       ├── res/values{, -zh-rCN}/strings.xml  # 英文 + 中文文案
│       ├── res/xml/file_paths.xml   # FileProvider 导出路径
│       ├── cpp/                        # ★ 原生源码（CMakeLists.txt + Camera3/YuvConverter/ImageUtils/JniBridge + libjpeg-turbo）
│       ├── jniLibs/{arm64-v8a}/libcamera3.so   # buildNative 产物（源码在 src/main/cpp）
│       └── resources/META-INF/xposed/  # module.prop / java_init.list / native_init.list / scope.list
├── app/src/test/                   # 单元测试（LogcatParserTest 等）
├── build.gradle / settings.gradle / gradle.properties / gradle/libs.versions.toml
├── docs/reverse/libcamera3/       # 预编译原生库反编译还原成果（报告/重建头文件/Ghidra 伪代码）
└── local.properties                # 本机 SDK 路径，不入库
```

> 注意：`app/src/main/cpp/` 已重建（CMakeLists + Camera3/YuvConverter/ImageUtils/JniBridge，
> 内含静态链入的 `libjpeg-turbo/`）；`externalNativeBuild` 已启用。
> 常规快速构建仍直接使用 `jniLibs` 里的 `.so`（`buildNative` 可重新编译）。

## 4. 核心架构与数据流

### 4.1 总体流程

```mermaid
flowchart LR
    A[宿主 App UI] -->|XposedService IPC| B[ConfigRepository 同步远程配置]
    B --> C[SourceManager.validMedia]
    C --> D{Hook 路径}
    D --> E[Camera1Hooker]
    D --> F[Camera2Hooker]
    D --> G[ImageReaderHooker]
    D --> H[WebRTCHooker]
    E & F & G & H --> I[NativeBridge JNI]
    I <--> J[libcamera3.so 原生引擎]
    K[ExoPlayer/Canvas 渲染所选媒体] --> I
```

### 4.2 配置流（宿主 → Hook 进程）

1. 宿主 UI 通过 `ConfigRepository`（本地 `SharedPreferences("camera_magic_config")`）读写配置；
2. `XposedServiceHelper.registerListener` 绑定成功后 `syncAllToRemote()` 把本地全部键推到
   `service.getRemotePreferences("camera_magic_config")`；
3. 媒体文件通过 `openRemoteFile(fileName)` 写入 Hook 进程可读的文件描述符
   （`prepareRemoteMedia`），`MagicHook.openRemoteFile` 在 Hook 侧读取；
4. Hook 进程内 `SourceManager.refreshPrefs()` 读取同一组键，计算 `validMedia`
   （本地视频/图片或 RTSP），并支持**按包覆盖**
   （`app_hook_<pkg>`、`app_media_mode_<pkg>`、`app_remote_photo/video_<pkg>`）。

### 4.3 运行流（Hook 侧）

1. `MagicHook.onPackageReady`（仅 `isFirstPackage`）→ `SourceManager.init` →
   Hook `Application.onCreate` 取得 `appContext`，并注册前台 Activity 监听，
   首个 Activity 启动时 `refreshAndDispatch()`（重新解析媒体、可弹 Toast）；
2. `Camera1Hooker` / `Camera2Hooker`：把应用真实预览 Surface 换成 `BlackHole`
   假 Surface，原 Surface 通过 `NativeBridge.addRenderTarget` 交给原生引擎；
3. 同一时刻 `Camera3.start` 用 ExoPlayer 播放所选视频/RTSP（或 Canvas 以 ~30fps
   绘制静态图）到 OES 纹理 → `SurfaceTexture` → 原生引擎注入到目标 Surface；
   `main_adapt_landscape`（横屏适配）开启时交换上报帧宽高并把图片 EXIF 方向烘焙进
   像素；`main_manually_rotate` 变化时通过改写 `updateCameraBaseData` 的
   sensorOri/displayOri 实时生效；
4. `ImageReaderHooker`：`format=256`(JPEG) 拍照时用所选图片按原始尺寸缩放替换
   （保留 EXIF，按字节数二分搜索压缩质量，JPEG 结果按媒体+开关缓存）；
   `format=35`(YUV) 走 `overwriteYuvBuffer` 原生覆盖；`main_fix_photo_rotation`
   开启时忽略相机 EXIF、按媒体自身方向烘焙旋转；Camera1 拍照路径同样支持
   （关闭时走原生 `overwriteJPEGBytes`）；
5. `WebRTCHooker`：解析 `org.webrtc.Logging.nativeLog` 中的 rotation 消息同步旋转，
   会话停止时释放 Camera3 与渲染目标。

### 4.4 Xposed 元数据

| 文件 | 内容 | 含义 |
| --- | --- | --- |
| `module.prop` | `id=com.nothing.camera2magic`，min/targetApiVersion=102 | LSPosed 模块声明 |
| `java_init.list` | `com.nothing.camera2magic.MagicHook` | Java 入口类 |
| `native_init.list` | `camera3` | 原生初始化入口（`System.loadLibrary("camera3")`） |
| `scope.list` | `tv.danmaku.bili` | 静态作用域：仅哔哩哔哩（`staticScope=false`） |

## 5. 关键类速查表

| 类 | 职责 | 关键要点 |
| --- | --- | --- |
| `MagicHook` | Xposed 入口 | 加载 .so、初始化 SourceManager、装配 4 个 Hooker（Camera1/Camera2/ImageReader/WebRTC）、前台 Toast |
| `GlobalState` | 进程内全局态 | `appContext`、`processName`、`activityCount`（@Volatile） |
| `SourceManager` | 配置解析中心 | 所有开关与媒体键的单一读取点；`readyForHook = moduleEnabled && appHookEnabled`；监听 `main_manually_rotate` / `main_fix_photo_rotation` / `main_adapt_landscape` 变化并实时重发原生 |
| `ConfigRepository` | 宿主侧配置读写 | 每个 setter 同时写本地 + 远程；按包配置、媒体上传、scope 查询 |
| `HookManager` | Hook 基础设施 | `safeHook` 去重（WeakHashMap 集合）+ `runCatching` 容错 |
| `Camera1Hooker` | 老版 Camera API | open/setPreview*/startPreview/stop/release/回调/拍照 |
| `Camera2Hooker` | Camera2 API | openCamera、createCaptureSession*、add/removeTarget、Surface 替换 |
| `ImageReaderHooker` | 拍照/取帧替换 | JPEG 替换 + EXIF 保留 + 质量二分；YUV 原生覆盖；JPEG 缓存 |
| `WebRTCHooker` | WebRTC 适配 | 解析 rotation 日志、会话结束清理 |
| `Camera3` / `Camera3Extended` | 渲染端 | ExoPlayer/Canvas → OES 纹理；横屏适配交换帧宽高/烘焙 EXIF；拍照时切自然尺寸；单例 HandlerThread("Camera3") |
| `MagicDataSource` | media3 DataSource | 基于 ParcelFileDescriptor 读取，支持 seek |
| `NativeBridge` | JNI 桥 | 全部 `external fun` 的声明（见下） |
| `BlackHole` | 假 Surface 池 | `WeakHashMap<Surface, BH>`，替换真实预览面；`clear()` 统一释放 |
| `MediaPathResolver` | 宿主侧路径展示 | 把 content:// 媒体解析为可展示的真实路径（MediaStore DATA / RELATIVE_PATH） |
| `Dog` | 日志 | 全局 TAG `VCX`；宿主进程内存缓冲 + logcat 桥接（root 时走 su），`StateFlow<List<LogEntry>>` 上限 1000 条；logcat 行解析为纯 JVM 函数并带单元测试 |

`NativeBridge` 的 JNI 函数清单：`createOESTexture`、`notifyFrameAvailable`、
`setSurfaceTexture`、`getSurfaceInfo`、`updateCameraBaseData`、`updateManualRotation`、
`addRenderTarget`(两个重载)、`removeRenderTarget`、`clearTargets`、
`updateAlgorithmSize`、`updateFrameInfo`、`overwriteYuvBuffer`(两个重载)、
`overwriteJPEGBytes`；另有 Kotlin 侧辅助 `ensureBuffer` / `frameUpdated`。

## 6. 配置键（SharedPreferences `camera_magic_config`）

| 键 | 类型/默认 | 含义 |
| --- | --- | --- |
| `main_module_enabled` | Boolean=true | 模块总开关 |
| `main_play_sound` / `main_enable_log` / `main_show_toast` | Boolean=false/false/true | 播放声音 / 日志 / Toast |
| `main_compress_jpeg` | Boolean=true | 拍照替换时压缩 JPEG |
| `main_inject_menu` | Boolean=false | 向目标相机应用注入菜单（当前仅宿主 UI 开关，Hook 侧尚未实现） |
| `main_fix_photo_rotation` | Boolean=false | 拍照旋转修正：忽略相机 EXIF，按媒体自身方向烘焙 |
| `main_adapt_landscape` | Boolean=false | 横屏适配：预览/拍照按媒体自然方向显示 |
| `main_manually_rotate` | Int=0 | 手动旋转（0/90/180/270 索引） |
| `media_source` | Int=0 | 0 本地，1 网络 |
| `local_media_type` | Int=0 | 0 视频，1 图片 |
| `remote_video_file` / `remote_image_file` | String? | 上传后的远程文件名 |
| `network_rtsp_uri` | String? | RTSP 地址（media3-exoplayer-rtsp 播放） |
| `local_video_uri` / `local_image_uri` | String? | 宿主侧持久化 URI（takePersistableUriPermission） |
| `hook_enabled_packages` | String(逗号分隔) | 启用 Hook 的包集合 |
| `app_hook_<pkg>` | Boolean=true | 单应用 Hook 开关 |
| `app_media_mode_<pkg>` | global/photo/video | 单应用媒体模式 |
| `app_photo_uri_<pkg>` / `app_video_uri_<pkg>` | String? | 单应用在宿主侧选择的持久化 URI（AppConfig 页面） |
| `app_remote_photo_<pkg>` / `app_remote_video_<pkg>` | String? | 单应用覆盖的媒体文件 |
| `theme_*` | 见 `ThemeConfig` | 主题全部键（dark_mode/pure_black/monet/palette/accent/blur/floating_bar/floating_bottom_bar_style/bottom_bar_mode/density_scale/predictive_back） |
| `main_hook_mode` | "Camera2" | Hook 模式（UI 展示用） |

## 7. 构建与发布规范

### 7.1 常规构建（推荐，快）

```powershell
.\gradlew.bat assembleRelease   # 或 assembleDebug
```

- 直接使用 `src/main/jniLibs` 预编译的 `libcamera3.so`；
- `afterEvaluate` 会**自动禁用所有 CMake / externalNativeBuild 任务**，除非本次命令含 `buildNative`；
- release 使用 `app/cam2magic.keystore` 签名（storePassword/keyPassword/alias 均为
  `camera2` / `cam2magic`），`minifyEnabled true + shrinkResources true`；
- ABI split 只产出 **arm64-v8a**（`splits.abi.include`），输出名
  `CAM2Magic-1.1.2-arm64-v8a.apk`（`androidComponents.onVariants` 重命名）。

### 7.2 原生构建（重新编译 libcamera3.so 时才用）

```powershell
.\gradlew.bat buildNative
```

`buildNative` 会：删除 `src/main/jniLibs`、`release/`、`.cxx` → 依赖
`stripReleaseDebugSymbols` → 把 stripped 的 `libcamera3.so` 拷回 `jniLibs`。
✅ `src/main/cpp/CMakeLists.txt` 已存在；`buildNative` 通过 `ExternalProject_Add`
先编译 `libjpeg-turbo`（`libturbojpeg.a`），再链接生成 `libcamera3.so`。
首次构建或改动 CMake 后建议 `gradlew clean buildNative`（避免旧中间产物被拷回）。

### 7.3 版本号

- `versionName = 1.1.2`（`major.minor.patch` 手写常量）；
- `versionCode = versionCodeOffset(0) + git 提交数`（`git rev-list --count HEAD`，
  失败时回退为 1）。项目内已建立 git 仓库（`C:\Users\fdhyr\Camera2Magic`），
  每次发布前新增提交即可让 versionCode 递增（截至本文档更新时共 2 次提交，
  versionCode=2）。

### 7.4 依赖与仓库

- `settings.gradle`：google() + mavenCentral() + `api.xposed.info` + jitpack；新增依赖需更新
  `gradle/libs.versions.toml`（version catalog 风格，禁止在 build.gradle 里硬编码新版本号）。

## 8. 编码与工具规范（重要）

1. **所有源文件均为 UTF-8（无 BOM）编码**，含中文注释与资源。
   - Windows 上 `PowerShell 5.1` 默认按 GBK 读取文件，中文会显示为乱码
     （如 `鐗堟湰鎺у埗鍙橀噺`）。读取时请显式指定：
     ```powershell
     Get-Content -Encoding UTF8 .\app\build.gradle
     [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
     ```
   - 写文件务必保持 UTF-8，**不要另存为 GBK**，否则会造成中文乱码回归。
2. 修改 Hook 类时保持现有模式：实现 `HookManager`，用
   `param.classLoader.safeHook(类名) { ... }` 注册；动态回调类用
   `javaClass.safeHook { ... }` 去重后 Hook。
3. 日志一律走 `com.nothing.camera2magic.utils.Dog`（i/w/e），tag 使用类内
   常量（如 `[CAM2]`），`enabled` 参数传 `SM.enableLog`；**不要直接 Log.d/x**。
4. 进程内共享对象用 `object` 单例；跨线程可见字段加 `@Volatile`；Hook 侧的
   对象引用用 `WeakReference` / `WeakHashMap` 防止内存泄漏（BlackHole、hookedClasses 都是）。
5. Hook 拦截里任何可能抛异常的代码用 `runCatching` 包住，失败只记日志，不阻断原调用。
6. 每个拦截器入口先判 `SM.readyForHook`（模块开关 + 应用开关），不满足直接 `chain.proceed()`。
7. `NativeBridge` 是 JNI 契约的唯一来源：新增/修改 native 函数时，Java 声明与
   `libcamera3.so` 导出必须同步，否则运行期 `UnsatisfiedLinkError`。
8. UI 使用 Miuix 组件（`top.yukonga.miuix.kmp.*`），主题基于
   `ThemeController` + `MiuixTheme`（由 `Camera2MagicTheme` 统一装载）；
   新增页面文案进 `res/values/strings.xml`（英文）+ `values-zh-rCN/strings.xml`（中文）。
9. 导航：新增页面在 `Route` sealed 层级加 @Serializable 条目，`Navigator.push/pop`，
   导航栈经 kotlinx.serialization JSON 持久化（`NavBackStackSaver`）。
10. 不修改 `app/cam2magic.keystore`、`proguard-rules.pro` 中 native keep 规则、
    以及 `jniLibs/*/libcamera3.so` 的 ABI 目录结构。

## 9. 已知坑与遗留问题（改动前必读）

- **RTSP 播放已实现**（依赖 `media3-exoplayer-rtsp`），但宿主 UI 还没有 RTSP 地址输入入口，目前仅能通过 `network_rtsp_uri` 配置。
- `main_inject_menu`（注入菜单）目前只是宿主 UI 上的开关，Hook 侧没有对应实现；不要把它当作已生效的功能。
- `NativeBridge.updateManualRotation` 在预编译 `libcamera3.so` 中没有实际读取点；
  手动旋转/横屏适配通过 `SourceManager.applyManualRotationToNative()` 改写
  `updateCameraBaseData` 的 sensorOri/displayOri 生效，改动旋转逻辑时不要只调
  `updateManualRotation`。
- `NetworkHooker`（HttpURLConnection/OkHttp 网络上传替换）与 `WorkMode` 枚举已删除：前者不再需要，后者无任何使用点。
- `MagicHook` 只在 `isFirstPackage` 时装配 Hook；scope 目前仅 `tv.danmaku.bili`，
  新增作用域包后需同步修改 `scope.list` 与 `hook_enabled_packages` 逻辑。
- `ImageReaderHooker` 中 format=256 的替换依赖 `magic.openRemoteFile` 与
  `SM.validMedia`；替换失败时返回原图而非崩溃。
- `BlackHole.clear()` 在 Camera 关闭/会话结束/WebRTC 停止时调用；新 Hook 路径
  结束时也要清理，否则 Surface 泄漏。
- `Camera3` 是**单例状态**（companion object 持有 player/surface），多个相机实例
  同时打开时共享同一渲染端，修改需谨慎。
- `externalNativeBuild` 已启用并指向 `src/main/cpp/CMakeLists.txt`；
  `buildNative` 由源码构建（libjpeg-turbo 3.0.4 静态链入），原始预编译 `.so` 有备份
  （见反编译报告 evidence/original-so/）。
- 原生库已从还原成果重建为可编译源码（`app/src/main/cpp/`），JNI 契约与 Kotlin
  `NativeBridge.kt` 一致；新增或修改 native 行为前先读 `docs/reverse/libcamera3/`
  报告与 `NativeBridge-JNI.md`（重建头文件是语义参考，实现以 `*.cpp` 为准）。
- 项目 git 仓库位于项目目录内，已初始化并有提交；`versionCode` 随提交数递增。

## 10. 改动 Checklist（AI 自检）

- [ ] 新增/修改 Hook：实现 `HookManager`、在 `MagicHook` 装配、`safeHook` 去重、先判 `readyForHook`、异常用 `runCatching` 包住、结束时清理 `BlackHole`/`Camera3`。
- [ ] 新增配置：同时在 `SourceManager`（Hook 侧读取）与 `ConfigRepository`（宿主侧读写+远程同步）添加，键名保持 `snake_case` 一致。
- [ ] 新增 UI：Miuix 组件、字符串进 `strings.xml`（en + zh-rCN）、导航走 `Route` + `Navigator`、ViewModel 经 `ViewModelFactory` 注册。
- [ ] 改动原生：更新 `NativeBridge` 声明、重新运行 `buildNative` 生成 `jniLibs`、检查 proguard keep。
- [ ] 构建验证：`.\gradlew.bat assembleDebug` 至少能过编译；改动签名/版本/ABI 时核对 `app/build.gradle` 常量。
- [ ] 改动 `Dog` / logcat 解析等纯 JVM 逻辑时跑 `.\gradlew.bat testDebugUnitTest` 验证。
- [ ] 编码检查：所有改动文件保持 UTF-8，中文注释在 UTF-8 读取下无乱码。
