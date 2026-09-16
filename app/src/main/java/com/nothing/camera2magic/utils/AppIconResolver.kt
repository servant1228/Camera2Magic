package com.nothing.camera2magic.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import org.xmlpull.v1.XmlPullParser

/** 已安装的启动器图标包（用于选择列表）。 */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * 解析应用图标，支持使用已安装的启动器图标包（Nova / Lawnchair 等 ADW 格式）。
 *
 * Android 没有系统级图标包 API，「当前桌面使用的图标包」存在启动器私有配置里，第三方读不到。
 * 因此这里由用户在应用内显式选择一个图标包，再通过该包 APK 的 `appfilter.xml` 做映射。
 * 访问其它已安装包资源的开销可控（无需存储权限），解析结果与位图都会缓存。
 */
class AppIconResolver(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // packPackage -> 解析结果（null 表示该包没有 appfilter，已探查过）
    private val packCache = LinkedHashMap<String, ParsedPack?>()

    private class ParsedPack(
        val resources: Resources,
        val byComponent: Map<String, String>,
        val byPackage: Map<String, String>,
    )

    /**
     * 取 [packageName] 的图标位图。当 [packPackage] 为空或图标包中没有该应用时，
     * 回退到应用自带图标。
     */
    fun load(packPackage: String?, packageName: String, sizePx: Int = 96): Bitmap? {
        val pack = packPackage.orEmpty()
        val cacheKey = "$pack|$packageName|$sizePx"
        bitmapCache.get(cacheKey)?.let { return it }
        val bitmap = runCatching {
            if (pack.isNotEmpty()) resolveFromPack(pack, packageName, sizePx) else null
        }.getOrNull() ?: loadSystemIcon(packageName, sizePx)
        if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun resolveFromPack(pack: String, packageName: String, sizePx: Int): Bitmap? {
        val parsed = parsedPack(pack) ?: return null
        val drawableName = lookupDrawableName(parsed, packageName) ?: return null
        var id = parsed.resources.getIdentifier(drawableName, "drawable", pack)
        if (id == 0) id = parsed.resources.getIdentifier(drawableName, "mipmap", pack)
        if (id == 0) return null
        val drawable = runCatching { parsed.resources.getDrawable(id, null) }.getOrNull() ?: return null
        return renderDrawable(drawable, sizePx)
    }

    private fun lookupDrawableName(parsed: ParsedPack, packageName: String): String? {
        // 优先按启动 Activity 的 component 精确匹配，再退回包级匹配
        val component = runCatching { pm.getLaunchIntentForPackage(packageName)?.component }.getOrNull()
        if (component != null) {
            parsed.byComponent["${component.packageName}/${component.className}"]?.let { return it }
        }
        return parsed.byPackage[packageName]
    }

    private fun loadSystemIcon(packageName: String, sizePx: Int): Bitmap? =
        runCatching { renderDrawable(pm.getApplicationIcon(packageName), sizePx) }.getOrNull()

    @Synchronized
    private fun parsedPack(pack: String): ParsedPack? {
        if (packCache.containsKey(pack)) return packCache[pack]
        if (packCache.size >= MAX_CACHED_PACKS) {
            packCache.keys.firstOrNull()?.let { packCache.remove(it) }
        }
        val parsed = runCatching { parsePack(pack) }.getOrNull()
        packCache[pack] = parsed
        return parsed
    }

    private fun parsePack(pack: String): ParsedPack {
        val resources = pm.getResourcesForApplication(pack)
        var appFilterId = resources.getIdentifier("appfilter", "xml", pack)
        if (appFilterId == 0) appFilterId = resources.getIdentifier("appfilter", "raw", pack)
        if (appFilterId == 0) return ParsedPack(resources, emptyMap(), emptyMap())
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        val parser = resources.getXml(appFilterId)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val normalized = parser.getAttributeValue(null, "component")?.let { normalizeComponent(it) }
                    if (!drawable.isNullOrEmpty() && normalized != null) {
                        byComponent[normalized] = drawable
                        val pkg = normalized.substringBefore('/')
                        if (pkg.isNotEmpty() && !byPackage.containsKey(pkg)) byPackage[pkg] = drawable
                    }
                }
                event = parser.next()
            }
        } finally {
            runCatching { parser.close() }
        }
        return ParsedPack(resources, byComponent, byPackage)
    }

    private fun renderDrawable(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val MAX_CACHED_PACKS = 4
        private const val COMPONENT_PREFIX = "ComponentInfo{"

        // ADW / Nova / Lawnchair 等图标包通用的 action 声明
        private val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.dlto.atom.launcher.THEME",
            "com.smartlauncher.theme",
            "com.gtp.nextlauncher.theme",
            "org.zeam.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "app.lawnchair.ICONPACK",
        )

        private fun normalizeComponent(raw: String): String? {
            var value = raw.trim()
            if (value.startsWith(COMPONENT_PREFIX)) value = value.removePrefix(COMPONENT_PREFIX)
            if (value.endsWith("}")) value = value.removeSuffix("}")
            value = value.trim()
            return value.takeIf { it.isNotEmpty() && it.contains('/') }
        }

        /** 枚举设备上声明了图标包 action 的应用（用于选择列表）。 */
        fun loadInstalledIconPacks(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            val result = LinkedHashMap<String, IconPackInfo>()
            ICON_PACK_ACTIONS.forEach { action ->
                val intent = Intent(action)
                runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()).forEach { info ->
                    info.activityInfo?.packageName?.let { register(pm, context.packageName, it, result) }
                }
                runCatching { pm.queryIntentServices(intent, 0) }.getOrDefault(emptyList()).forEach { info ->
                    info.serviceInfo?.packageName?.let { register(pm, context.packageName, it, result) }
                }
            }
            return result.values.sortedBy { it.label.lowercase() }
        }

        private fun register(
            pm: PackageManager,
            selfPackage: String,
            packageName: String,
            out: MutableMap<String, IconPackInfo>,
        ) {
            if (packageName == selfPackage || out.containsKey(packageName)) return
            val label = runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
            out[packageName] = IconPackInfo(packageName, label)
        }
    }
}

val LocalAppIconResolver = staticCompositionLocalOf<AppIconResolver> {
    error("No AppIconResolver provided")
}

val LocalInstalledIconPacks = staticCompositionLocalOf<List<IconPackInfo>> { emptyList() }
