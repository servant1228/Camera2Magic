# Camera2 Magic

> Android 虚拟摄像头 Xposed 模块，基于 [Atomos-X/Camera2Magic](https://github.com/Atomos-X/Camera2Magic) 二次开发。

[![Build Release APK](https://github.com/servant1228/Camera2Magic/actions/workflows/build-release.yml/badge.svg)](https://github.com/servant1228/Camera2Magic/actions/workflows/build-release.yml)

## 简介

Camera2 Magic 是一个基于 **LSPosed / libxposed（API 102）** 的 Android 虚拟摄像头模块：当被 Hook 的应用调用
Camera1 / Camera2 / ImageReader / WebRTC 等路径请求摄像头时，用你选择的本地视频、图片或 RTSP 网络流替换真实画面。

## 特性

- 摄像头替换
  - Camera1 / Camera2 预览替换
  - ImageReader 拍照替换（JPEG 保留 EXIF；YUV 走原生覆盖）
  - WebRTC（camera2）路径适配
- 媒体源
  - 本地视频 / 静态图片
  - RTSP 网络流（media3-exoplayer-rtsp）
- 精细化配置
  - 按应用单独设置媒体与开关（AppConfig）
  - 手动旋转、拍照旋转修正
  - 播放声音、Toast、日志开关
- 界面
  - Miuix（HyperOS 风格）Compose UI
  - 多主题：深色 / 纯黑 / Monet 动态取色 / 自定义强调色 / 模糊效果等

## 使用前提

- Android 13+（`minSdk 33`，`targetSdk 36`）
- 已安装并激活 LSPosed（模块要求 libxposed API 102）
- 设备具备所选媒体对应的解码能力

## 使用步骤

1. 在 LSPosed 管理器中激活本模块，并勾选作用域；
2. 打开模块 UI，选择本地视频/图片作为虚拟摄像头内容（或配置 RTSP 地址）；
3. 开启模块总开关，进入目标应用即可生效；
4. 可选：为单个应用单独配置媒体与开关。

> 注意：媒体文件需要可被模块与目标应用访问；拍照替换仅对支持 JPEG/YUV 输出的相机路径生效。

## 构建

### 常规构建

```powershell
.\gradlew.bat assembleRelease
```

- 使用预编译的 `libcamera3.so`（`app/src/main/jniLibs/arm64-v8a/`），原生源码不入库；
- 输出：`CAM2Magic-2.0.0-arm64-v8a.apk`（release 签名由本地 `app/keystore.properties` 或 CI secrets 提供）。

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
