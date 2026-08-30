package com.nothing.camera2magic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 状态语义色 token。healthy/danger 使用固定色谱；
 * Monet 开启时 running 状态跟随动态取色，警示语义保持固定色，不随壁纸漂移。
 */
object StatusColors {

    val healthy: Color
        @Composable @ReadOnlyComposable get() = if (LocalAppDarkMode.current) GreenDark else GreenLight

    val danger: Color
        @Composable @ReadOnlyComposable get() = if (LocalAppDarkMode.current) RedDark else RedLight

    @Composable @ReadOnlyComposable
    fun runState(state: RunState): Color = when (state) {
        RunState.Running ->
            if (LocalAppMonetEnabled.current) MiuixTheme.colorScheme.primary else healthy
        RunState.Stopped -> danger
    }

    @Composable @ReadOnlyComposable
    fun runStateContainer(state: RunState): Color {
        val isDark = LocalAppDarkMode.current
        return when (state) {
            RunState.Running ->
                if (LocalAppMonetEnabled.current) {
                    MiuixTheme.colorScheme.secondaryContainer
                } else if (isDark) {
                    DarkGreenBg
                } else {
                    LightGreenBg
                }
            RunState.Stopped -> if (isDark) DarkRedBg else LightRedBg
        }
    }

    // —— 固定色谱：非 Monet 模式及警示语义色不随主题漂移 ——
    private val GreenLight = Color(0xFF4CAF50)
    private val GreenDark = Color(0xFF81C784)
    private val RedLight = Color(0xFFE53935)
    private val RedDark = Color(0xFFEF5350)
    private val LightGreenBg = Color(0xFFDFFAE4)
    private val DarkGreenBg = Color(0xFF1A3825)
    private val LightRedBg = Color(0xFFFDE8E8)
    private val DarkRedBg = Color(0xFF3A2020)
}

enum class RunState { Running, Stopped }
