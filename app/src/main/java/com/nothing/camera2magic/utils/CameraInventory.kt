package com.nothing.camera2magic.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * 宿主侧镜头清点：**纯诊断展示**（主页只显示一个数字），不参与任何 Hook 门控或媒体选择。
 *
 * 统计口径 = **前置 + 后置**（按 `LENS_FACING` 分类），即「App 真正会去打开的那几个镜头」。
 * 不做「几颗物理镜头」的推断：多颗后置在多数机型上被聚合成同一个逻辑相机 id（见
 * `Camera2Hooker` 的 `lens:` 日志里 `physical=[2,3]` 那种），id 数 ≠ 物理镜头数，
 * 各家暴露方式也不统一，与其给个不准的数不如只给朝向分类这个可靠口径。
 *
 * **`LENS_FACING` 是否可读由机型决定**：它属于各厂商在
 * [CameraCharacteristics.getKeysNeedingPermission] 里自行声明的那批受限键，
 * AOSP 并不保证它对无 CAMERA 权限的调用方开放。本模块**有意不申请 CAMERA**
 * （见 AGENTS「权限面很窄」：对一个虚拟摄像头模块来说那本身就是检测指纹），
 * 所以这里必须容忍读不到——读不到时返回 null，UI 显示 `—`，绝不猜。
 * 需要按焦距做精确识别时走 Hook 侧（那边用目标应用身份，必然有 CAMERA）。
 */
object CameraInventory {

    /**
     * 前置 + 后置的镜头数。任一 id 的朝向读不到就整体返回 null（宁可显示 `—` 也不给半对的数字）；
     * 枚举失败（设备策略禁用、无相机服务等）同样返回 null。
     */
    fun count(context: Context): Int? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@runCatching null
        manager.cameraIdList.count { id ->
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) ?: return@runCatching null
            facing == CameraCharacteristics.LENS_FACING_FRONT ||
                facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
}
