# libcamera3 — 自研原生渲染引擎

原项目依赖预编译闭源 `libcamera3.so`（源码不公开、无法维护）。本目录是**完全自研的替代实现**：
自有源码、自有构建，产物仅依赖系统库（`libandroid` / `liblog` / `libEGL` / `libGLESv3`），
不链接 libjpeg、不链接 libc++，stripped 后约 60KB（旧闭源库约 1.1MB）。

## 文件

| 文件 | 职责 |
| --- | --- |
| `engine.h` / `engine.cpp` | 核心：单例 + 独立渲染线程（EGL/GLES3），预览合成、YUV 读回、目标面管理 |
| `jpeg_encoder.h` / `jpeg_encoder.cpp` | 自研 baseline JPEG 编码器（NV21 → 4:2:0 + EXIF Orientation） |
| `native_bridge.cpp` | `JNI_OnLoad` + `RegisterNatives`，把 Kotlin `NativeBridge` 的 16 个方法绑到引擎 |
| `gl_abi.h` | 引擎用到的 EGL/GLES3 声明与常量（避免依赖 Khronos/NDK 头，Termux 也能编译） |
| `build_local.sh` | 在 Termux 上直接编译做语法/链接自检（不经 Gradle） |
| `CMakeLists.txt` | 正式构建入口，被 `app/build.gradle` 的 `buildNative` 使用 |

## 架构

```
目标应用进程
├── Camera3.kt（Kotlin 侧）
│     ExoPlayer / lockHardwareCanvas → SurfaceTexture(oesTexture)
│     每帧 onFrameAvailable → NativeBridge.notifyFrameAvailable()
└── libcamera3.so
      render thread（唯一持 GL 的线程，线程名由 Kotlin 的 HandlerThread 提供者为 "Camera3"）
      ├── ASurfaceTexture_updateTexImage + getTransformMatrix（取生产者帧）
      ├── 对每个已注册 ANativeWindow：eglMakeCurrent → 预览 shader → eglSwapBuffers
      └── 按需把同一帧渲染成 NV21（R8 FBO + glReadPixels）供 YUV/JPEG 消费
```

- **线程模型**：一个渲染线程持有 EGL context；所有共享状态由 `pthread_mutex_t` + `pthread_cond_t`
  保护。消费者（App 的相机回调线程）通过 `ensureYuvLocked()` 请求指定尺寸的 YUV 帧并等待；
  渲染线程即使没有新生产者帧，也会用当前纹理重渲染一次来满足请求。等待有 400ms 上限，
  超时给 App 一帧中性黑帧（Y=0 / UV=128），绝不无限阻塞。
- **YUV 按需渲染**：旧实现在 Camera1 下每帧都按**拍照尺寸**（可能 12MP）做 GPU 渲染 + 读回，
  浪费严重。现在默认只按预览尺寸产出；`overwriteJPEGBytes()` 时才按拍照尺寸渲染一次，
  且 `overwriteYuvBuffer([BII)` 由调用方显式给出尺寸，不再靠字节数猜。
- **NV21 布局**：YUV 走自家 fragment shader 直接产生 NV21（Y 平面 + VU 交错平面），
  Camera1 的 `onPreviewFrame` 是整块 `memcpy`，Camera2 平面路径按 `rowStride/pixelStride` 逐行拆写。
- **JPEG**：自研编码器直接吃 NV21（无需 RGB 转换），带 EXIF Orientation（后置 6 / 前置 8，与
  旧库行为一致）。失败返回 `null`，Kotlin 侧回退原始相机 JPEG（旧库此路径会返回空数组）。

## 与旧闭源库的行为差异

- **保留**：预览旋转/镜像/裁剪矩阵、按应用（WhatsApp / 微信小程序 / Telegram / Instagram）的
  旋转豁免、Camera1 的 `displayOri % 180 == 90` 目标朝向分支、YUV 的 `360 - sensorOri`
  方向与 letterbox 缩放，全部按反汇编结果 1:1 复刻。
- **改进**：Camera1 的 YUV 尺寸改用预览尺寸（旧库用拍照尺寸，两者不一致时旧库会输出黑帧）；
  JPEG 只按需渲染；日志受 `main_enable_log` 门控（旧库常开输出）；
  **媒体方向自动对齐**（见下）。
- **有意保留为 no-op**：`updateManualRotation()` 只存值不读（旧库同样如此；手动旋转实际走
  `updateCameraBaseData`）。WebRTC 自动旋转因此与旧库行为一致，未擅自"修复"。

## 媒体方向自动对齐（相对旧库的行为差异）

旧引擎假定所选媒体已经是“传感器朝向”（手机上即横屏）：竖屏照片/视频会被原样画进
横屏取景面，用户只能靠 UI 的「手动旋转」把差出来的 90° 补回去。

现在 [engine.cpp](engine.cpp) 的 `alignSensorOrientation()` 在 `.so` 内部完成对齐：

- 输入：相机 `sensorOri`、媒体解码尺寸 `frameW/frameH`、媒体自身未应用的旋转
  `frameRot`（视频来自 ExoPlayer 的 `unappliedRotationDegrees`，图片来自 EXIF，
  由 `Camera3.kt` 通过 `updateFrameInfo()` 的第三个参数传入）。
- 规则：横屏传感器（`sensorOri % 180 == 90`，手机上的绝大多数情况）遇到**竖屏媒体**时
  额外旋转 -90°；竖屏传感器遇到横屏媒体时反向 +90°；然后把 `frameRot` 叠加上去。
- 横屏媒体保持原行为不变，用应用级旋转豁免表（WhatsApp / 微信小程序 / Telegram /
  Instagram）也照旧生效。

对齐后同一份有效 sensor 值同时喂给预览与 YUV 两条路径，所以两种输出方向一致。
「手动旋转」退化为用户级的额外偏移（默认 0 即可）；旧版为了工单而设成 90/180/270
的用户需把它改回 0，否则会叠上双倍旋转。

## 构建

```bash
# 正式（PC / CI）：产出 stripped libcamera3.so 并同步到 app/src/main/jniLibs/
./gradlew :app:buildNative

# 本机自检（Termux，需要 clang）：
app/src/main/cpp/build_local.sh [输出路径]
```

`buildNative` 会先清理 `jniLibs` 再编译，避免旧产物被 merge 进 APK（见根 AGENTS.md 构建一节）。
`app/src/main/cpp/build/` 是本机构建产物目录，不入库。

## JNI 契约

契约单点是 `app/src/main/java/com/nothing/camera2magic/hook/NativeBridge.kt`，
**新增/修改任何 `external fun` 时必须同步 `native_bridge.cpp` 的 `kNativeMethods` 表**，
否则不是编译错误而是运行期 `UnsatisfiedLinkError`。
