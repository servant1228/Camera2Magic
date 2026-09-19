package com.nothing.camera2magic.hook

import android.content.SharedPreferences
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.LensKeys
import com.nothing.camera2magic.utils.LensSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 测试：手写 SharedPreferences 假实现（不用 Robolectric/mockito，测试不得碰 Android framework），
 * 且全程不开日志——Dog 在 enabled=false 时短路，不会走到 android.util.Log。
 */
class SourceManagerTest {

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { map[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { map[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { map[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { map[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { map[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { map.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private class FakePrefs : SharedPreferences {
        val map: MutableMap<String, Any?> = mutableMapOf()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun seed(pkg: String, values: Map<String, Any?>): FakePrefs {
        GlobalState.processName = pkg
        return FakePrefs().apply {
            map["app_hook_$pkg"] = true
            values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }

    private fun reload(pkg: String, prefs: FakePrefs, slot: LensSlot): ValidMedia? {
        SourceManager.init(prefs)
        return SourceManager.activateSlot(slot)
    }

    @Test
    fun eachSlotUsesItsOwnMedia() {
        val pkg = "com.example.app"
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.FRONT, pkg) to "video",
                LensKeys.remoteVideo(LensSlot.FRONT, pkg) to "video_front.$pkg.mp4",
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "photo",
                LensKeys.remotePhoto(LensSlot.BACK, pkg) to "photo_back.$pkg.jpg",
            ),
        )

        assertEquals(MagicType.LOCAL_VIDEO, reload(pkg, prefs, LensSlot.FRONT)?.type)
        assertEquals(MagicType.LOCAL_IMAGE, reload(pkg, prefs, LensSlot.BACK)?.type)
    }

    @Test
    fun unconfiguredSlotInheritsLegacyMedia() {
        val pkg = "com.example.legacy"
        val prefs = seed(
            pkg,
            mapOf(
                "app_media_mode_$pkg" to "video",
                "app_remote_video_$pkg" to "video.$pkg.mp4",
            ),
        )

        // 没进过应用配置页 = 没有迁移标记：两槽都按旧语义共用一份媒体
        assertEquals(MagicType.LOCAL_VIDEO, reload(pkg, prefs, LensSlot.FRONT)?.type)
        assertEquals(MagicType.LOCAL_VIDEO, reload(pkg, prefs, LensSlot.BACK)?.type)
    }

    @Test
    fun migratedFlagSuppressesStaleLegacyKeys() {
        val pkg = "com.example.stale"
        // 迁移后旧键没删干净（远程残留）：回退链必须被标记掐断，否则删掉的媒体会复活
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                "app_media_mode_$pkg" to "photo",
                "app_remote_photo_$pkg" to "stale.$pkg.jpg",
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "video",
                LensKeys.remoteVideo(LensSlot.BACK, pkg) to "video_back.$pkg.mp4",
            ),
        )

        // 前置不能回退到陈旧的旧键（那张 stale 照片），也不借用后置那份：它就是没媒体
        assertNull(reload(pkg, prefs, LensSlot.FRONT))
        assertEquals(MagicType.LOCAL_VIDEO, reload(pkg, prefs, LensSlot.BACK)?.type)
    }

    @Test
    fun modeKeyDecidesWhichFileIsLive() {
        val pkg = "com.example.mode"
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.FRONT, pkg) to "photo",
                LensKeys.remotePhoto(LensSlot.FRONT, pkg) to "photo_front.$pkg.jpg",
                LensKeys.remoteVideo(LensSlot.FRONT, pkg) to "video_front.$pkg.mp4",
            ),
        )

        assertEquals("photo_front.$pkg.jpg", reload(pkg, prefs, LensSlot.FRONT)?.file)

        prefs.map[LensKeys.mediaMode(LensSlot.FRONT, pkg)] = "video"
        assertEquals("video_front.$pkg.mp4", reload(pkg, prefs, LensSlot.FRONT)?.file)

        // 特征读不到（slot=null）时沿用当前槽，不清空媒体
        assertEquals("video_front.$pkg.mp4", SourceManager.activateSlot(null)?.file)
    }

    @Test
    fun noMediaAnywhereYieldsNull() {
        val pkg = "com.example.empty"
        val prefs = seed(pkg, emptyMap())

        assertNull(reload(pkg, prefs, LensSlot.FRONT))
        assertNull(reload(pkg, prefs, LensSlot.BACK))
        assertEquals("No media selected.", SourceManager.toastMessage)
    }

    @Test
    fun noMediaMeansPassthroughWhileAppStaysEnabled() {
        val pkg = "com.example.passthrough"
        val prefs = seed(pkg, mapOf("app_hook_$pkg" to true))

        reload(pkg, prefs, LensSlot.BACK)

        // 没配媒体：不再替换画面（走真实相机），但应用仍然是「启用」态——
        // 记账与清理路径靠 appEnabled 跑，否则会漏掉上一轮渲染态的清理
        assertTrue(SourceManager.appEnabled)
        assertFalse(SourceManager.readyForHook)
    }

    @Test
    fun emptySlotDoesNotBorrowTheOtherSlotMedia() {
        val pkg = "com.example.perslot"
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "photo",
                LensKeys.remotePhoto(LensSlot.BACK, pkg) to "photo_back.$pkg.jpg",
                // 前置不配
            ),
        )

        assertEquals("photo_back.$pkg.jpg", reload(pkg, prefs, LensSlot.BACK)?.file)
        assertTrue(SourceManager.readyForHook)

        // 后置配了、前置没配：前置必须是「没媒体」= 输出真实画面，绝不能拿后置那份
        assertNull(reload(pkg, prefs, LensSlot.FRONT))
        assertFalse(SourceManager.readyForHook)
    }

    @Test
    fun perAppDisableTurnsEverythingOff() {
        val pkg = "com.example.off"
        val prefs = seed(
            pkg,
            mapOf(
                "app_hook_$pkg" to false,
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "photo",
                LensKeys.remotePhoto(LensSlot.BACK, pkg) to "photo_back.$pkg.jpg",
            ),
        )

        reload(pkg, prefs, LensSlot.BACK)

        assertFalse(SourceManager.appEnabled)
        assertFalse(SourceManager.readyForHook)
    }

    @Test
    fun networkModeResolvesToStreamUrl() {
        val pkg = "com.example.net"
        val url = "rtsp://192.168.1.10:8554/live"
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "network",
                LensKeys.streamUrl(LensSlot.BACK, pkg) to url,
            ),
        )

        val media = reload(pkg, prefs, LensSlot.BACK)
        assertEquals(MagicType.NETWORK_STREAM, media?.type)
        // URL 就存在 ValidMedia.file 里（与本地媒体的远程文件名同一字段）
        assertEquals(url, media?.file)
        assertTrue(SourceManager.readyForHook)
    }

    @Test
    fun networkModeWithoutUrlDoesNotResurrectLegacyMedia() {
        val pkg = "com.example.netempty"
        val prefs = seed(
            pkg,
            mapOf(
                // 未迁移的旧版共享媒体：两槽都能回退到它
                "app_media_mode_$pkg" to "video",
                "app_remote_video_$pkg" to "video.$pkg.mp4",
                // 这一槽显式选了网络流，但还没填地址
                LensKeys.mediaMode(LensSlot.BACK, pkg) to "network",
            ),
        )

        // 模式键已显式存在 = 整槽已配置：URL 为空就是 null，绝不能回退到旧视频
        assertNull(reload(pkg, prefs, LensSlot.BACK))
        // 另一槽没碰过，仍然按旧语义共用旧媒体
        assertEquals(MagicType.LOCAL_VIDEO, reload(pkg, prefs, LensSlot.FRONT)?.type)
    }

    @Test
    fun streamSlotDoesNotBorrowFromOtherSlot() {
        val pkg = "com.example.netperslot"
        val url = "http://example.com/live.m3u8"
        val prefs = seed(
            pkg,
            mapOf(
                LensKeys.migrated(pkg) to true,
                LensKeys.mediaMode(LensSlot.FRONT, pkg) to "network",
                LensKeys.streamUrl(LensSlot.FRONT, pkg) to url,
            ),
        )

        assertEquals(MagicType.NETWORK_STREAM, reload(pkg, prefs, LensSlot.FRONT)?.type)
        // 后置没配：必须是没媒体（透传真实画面），不能借用前置的流
        assertNull(reload(pkg, prefs, LensSlot.BACK))
        assertFalse(SourceManager.readyForHook)
    }
}
