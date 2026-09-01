package com.nothing.camera2magic.hook

import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Collections
import java.util.WeakHashMap
class WebRTCHooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {
    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[WebRTC]"
        private val ROTATION_REGEX = Regex("""(\d+)x(\d+).*rotation\s+(\d+)""")
        // 两条路径的单位不同，必须分开记：自动路径存角度（0/90/180/270），
        // 手动路径存 main_manually_rotate 的索引（0..3）。混用一个字段时，
        // 陈旧的自动角度 2 会误判为「手动索引 2 未变化」而抑制重新下发。
        private var lastAutoRotation = -1
        private var lastManualRotate = -1
        private lateinit var nativeLogInterceptor: (Chain) -> Any?
    }

    init {
        nativeLogInterceptor = { chain ->
            // 整体兜底：这里挂在 WebRTC 的日志函数上，目标 App 每条日志都会流经，
            // 任何异常都必须吞掉，否则等于让目标应用的日志调用崩溃
            runCatching {
                val tag = chain.args[1] as? String ?: ""
                val msg = chain.args[2] as? String ?: ""
                // 清理路径不判门控：会话已停止就必须释放，
                // 否则运行中关掉 app_hook_<pkg> 会永久泄漏 Surface/纹理
                if (tag == "Camera2Session" && msg.contains("Stop Camera2 session", ignoreCase = true)) {
                    Camera3().stop()
                    NB.clearTargets()
                    BlackHole.clear()
                    lastAutoRotation = -1
                    lastManualRotate = SourceManager.manuallyRotate
                    SourceManager.applyManualRotationToNative()
                } else if (SourceManager.readyForHook && msg.contains("rotation", ignoreCase = true)) {
                    // 旋转下发属于替换路径，未启用直接跳过
                    handleMessage(msg)
                }
            }.onFailure { e ->
                Dog.e(TAG, "nativeLog intercept failed: ${e.message}", e, SourceManager.enableLog)
            }
            chain.proceed()
        }

        val classLoader = param.classLoader
        // 注意：hook 目标是日志函数 org.webrtc.Logging.nativeLog。
        // 目标 App 关闭 WebRTC 日志或混淆掉该类，本功能整体静默失效（safeHook 只记警告）。
        classLoader.safeHook("org.webrtc.Logging") {
            hookNativeLog()
        }
    }

    private fun Class<*>.hookNativeLog() {
        val nativeLog = getDeclaredMethod("nativeLog",
            Int::class.java, String::class.java, String::class.java)
        magic.hook(nativeLog).intercept(nativeLogInterceptor)
    }

    private fun handleMessage(msg: String) {
        // 手动旋转优先：设置了全局手动旋转时，不采用 WebRTC 自动旋转
        if (SourceManager.manuallyRotate > 0) {
            if (lastManualRotate != SourceManager.manuallyRotate) {
                lastManualRotate = SourceManager.manuallyRotate
                SourceManager.applyManualRotationToNative()
            }
            return
        }
        lastManualRotate = 0
        val matchResult = ROTATION_REGEX.find(msg)
        matchResult?.let {
            val (_, _, r) = it.destructured
            val rotation = r.toInt()
            if (lastAutoRotation != rotation) {
                Dog.i(TAG, "WebRTC set rotation: $rotation", SourceManager.enableLog)
                lastAutoRotation = rotation
                NB.updateManualRotation(rotation)
            }
        }
    }
}
