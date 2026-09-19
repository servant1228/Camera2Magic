package com.nothing.camera2magic.utils

/**
 * 镜头槽位：只区分前置 / 后置。
 *
 * 为什么不做「一颗物理镜头一个槽」：现代机型普遍有 3~4 颗后置，但它们要么被聚合进同一个
 * 逻辑相机 id（App 变焦时 HAL 内部换物理镜头、不重开相机，Hook 侧完全看不到），要么三方
 * App 根本不会去 open（长焦/微距/深度）。按物理镜头分槽在机型间语义不一致，只有 LENS_FACING
 * 是唯一稳定的粒度。
 *
 * 槽位相关的配置键与远程文件名一律由 [LensKeys] 拼，Hook 侧与宿主侧共用，避免两侧手漂。
 */
enum class LensSlot(val id: String) {
    FRONT("front"),
    BACK("back");

    companion object {
        /**
         * Camera1 的 `CameraInfo.facing` 与 Camera2 的 `LENS_FACING` 编号一致（0=后置、1=前置）。
         * Camera1 的外接镜头（运行面值 2）在 compileSdk 37 的 `Camera.CameraInfo` 里已无对应常量，
         * 按「非前置即后置」归类是故意的。
         */
        fun ofFront(front: Boolean): LensSlot = if (front) FRONT else BACK
    }
}

/** 镜头媒体配置的键名单点（snake_case；宿主读写、Hook 侧读取都走这里）。 */
object LensKeys {
    fun mediaMode(slot: LensSlot, pkg: String) = "app_media_mode_${slot.id}_$pkg"
    fun remotePhoto(slot: LensSlot, pkg: String) = "app_remote_photo_${slot.id}_$pkg"
    fun remoteVideo(slot: LensSlot, pkg: String) = "app_remote_video_${slot.id}_$pkg"

    /** 网络视频流地址（rtsp/http/hls）。URL 是纯字符串，不走文件拷贝，Hook 侧直接读它。 */
    fun streamUrl(slot: LensSlot, pkg: String) = "app_stream_url_${slot.id}_$pkg"
    fun photoUri(slot: LensSlot, pkg: String) = "app_photo_uri_${slot.id}_$pkg"
    fun videoUri(slot: LensSlot, pkg: String) = "app_video_uri_${slot.id}_$pkg"

    /** 旧版不分镜头的键：仅作为未迁移应用的回退与一次性迁移的来源。 */
    fun legacyMediaMode(pkg: String) = "app_media_mode_$pkg"
    fun legacyRemotePhoto(pkg: String) = "app_remote_photo_$pkg"
    fun legacyRemoteVideo(pkg: String) = "app_remote_video_$pkg"
    fun legacyPhotoUri(pkg: String) = "app_photo_uri_$pkg"
    fun legacyVideoUri(pkg: String) = "app_video_uri_$pkg"

    /** 「旧键 → 双槽」迁移标记，每个应用只做一次。 */
    fun migrated(pkg: String) = "app_lens_migrated_$pkg"

    /** 远程媒体文件名基名：`<photo|video>_<slot>_<pkg>`，调用方按 MIME 追加扩展名。 */
    fun remoteFileBase(slot: LensSlot, mode: String, pkg: String) = "${mode}_${slot.id}_$pkg"
}
