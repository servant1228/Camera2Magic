# Camera2 Magic

> Android 虚拟摄像头 Xposed 模块，基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发。

[![Build Release APK](https://github.com/servant1228/Camera2Magic/actions/workflows/build-release.yml/badge.svg)](https://github.com/servant1228/Camera2Magic/actions/workflows/build-release.yml)

## 简介

Camera2 Magic 是一个基于 **LSPosed / libxposed（API 102）** 的 Android 虚拟摄像头模块：当被 Hook 的应用调用
Camera1 / Camera2 / ImageReader / WebRTC 等路径请求摄像头时，用你在该应用配置中选择的照片或视频替换真实画面。

## 特性

- 摄像头替换
  - Camera1 / Camera2 预览替换
  - ImageReader 拍照替换（JPEG 保留 EXIF；YUV 走原生覆盖）
  - WebRTC（camera2）路径适配
- 媒体源
  - 按应用配置照片 / 视频（本地媒体）
- 精细化配置
  - 按应用三态控制：关闭 / 照片 / 视频（AppConfig）
  - 手动旋转
  - 播放声音、Toast、日志开关
- 界面
  - Miuix（HyperOS 风格）Compose UI
  - 多主题：深色 / 纯黑 / Monet 动态取色 / 自定义强调色 / 模糊效果等

## 使用前提

- Android 14+（`minSdk 34`，`targetSdk 36`）
- 已安装并激活 LSPosed（模块要求 libxposed API 102）
- 设备具备所选媒体对应的解码能力

## 使用步骤

1. 在 LSPosed 管理器中激活本模块，并勾选作用域；
2. 在模块的“作用域”页进入目标应用配置，开启“启用 Hook”，选择“照片/视频”并选取媒体文件；
3. 进入目标应用即可生效（未配置媒体时不替换画面）。

> 注意：媒体文件需要可被模块与目标应用访问；拍照替换仅对支持 JPEG/YUV 输出的相机路径生效。

## 查看日志

先开启「启用日志」，然后通过 logcat 查看（tag 为 `VCX`）：

```bash
adb logcat -s VCX:*
```

> 应用内不再提供日志页；查看目标应用（Hook 进程）的日志同样通过 adb 完成。

## 构建

### 常规构建

```powershell
.\gradlew.bat assembleRelease
```

- 使用预编译的 `libcamera3.so`（`app/src/main/jniLibs/arm64-v8a/`），原生源码不入库；
- 输出：`CAM2Magic-<version>-arm64-v8a.apk`（release 签名由本地 `app/keystore.properties` 或 CI secrets 提供）。

## 目录结构

```text
app/
├── src/main/java/com/nothing/camera2magic/
│   ├── MagicHook.kt            # Xposed 入口
│   ├── hook/                   # Hook 引擎（Camera1/Camera2/ImageReader/WebRTC/渲染端）
│   ├── ui/                     # Compose UI（Miuix）
│   └── viewmodel/              # 配置仓库与 ViewModel
├── src/main/jniLibs/           # 预编译原生库 libcamera3.so（闭源，源码不入库）
└── src/main/resources/META-INF/xposed/   # module.prop / scope.list 等
```

## 声明

- 本项目基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发，原项目未附带 License，使用与分发请自行确认合规；
- 原生库 `libcamera3.so` 为预编译闭源产物，源码不在本仓库内；
- 本模块仅供学习交流，请勿用于任何违法或违规用途；由此产生的任何后果由使用者自行承担。

## 致谢

- 原项目：[Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic)
- 播放与渲染：[AndroidX media3（ExoPlayer）](https://developer.android.com/media/media3)
- 原生编码：[libjpeg-turbo](https://libjpeg-turbo.org/)
- UI 组件库：[Miuix](https://github.com/yukonga/Miuix)
- Hook 框架：[LSPosed](https://github.com/LSPosed/LSPosed) / [libxposed](https://github.com/libxposed/api)
