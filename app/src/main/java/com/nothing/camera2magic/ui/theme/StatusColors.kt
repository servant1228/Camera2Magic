package com.nothing.camera2magic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对齐 Mishka：healthy/danger 用固定色谱，running 状态用 healthy 而非跟随主题 primary。
 * 唯一例外：Monet 开启时 running 才跟随动态取色。
 */
object StatusColors {

    val healthy: Color
        @Composable @ReadOnlyComposable get() = if (LocalAppDarkMode.current) GreenDark else GreenLight

    val danger: Color
        @Composable @ReadOnlyComposable get() = if (LocalAppDarkMode.current) RedDark else RedLight

    @Composable @ReadOnlyComposable
    fun runState(state: RunState): Color = when (state) {
        RunState.Running -> healthy
        RunState.Stopped -> danger
    }

    @Composable @ReadOnlyComposable
    fun runStateContainer(state: RunState): Color {
        val isDark = LocalAppDarkMode.current
        return when (state) {
            RunState.Running -> if (isDark) DarkGreenBg else LightGreenBg
            RunState.Stopped -> if (isDark) DarkRedBg else LightRedBg
        }
    }

    @Composable @ReadOnlyComposable
    fun actionButtonContainer(kind: ActionKind): Color {
        val isDark = LocalAppDarkMode.current
        return when (kind) {
            ActionKind.Stop -> if (isDark) DarkRedBg else LightRedBg
            else -> if (isDark) DarkGreenBg else LightGreenBg
        }
    }

    @Composable @ReadOnlyComposable
    fun actionButtonContent(kind: ActionKind): Color = when (kind) {
        ActionKind.Stop -> danger
        else -> healthy
    }

    @Composable @ReadOnlyComposable
    fun logLevel(level: String): Color = when (level) {
        "E" -> if (LocalAppDarkMode.current) RedDark else RedLight
        "W" -> Orange
        "I" -> Blue
        else -> GreenLight
    }

    // —— 固定色谱，对齐 Mishka ——
    private val GreenLight = Color(0xFF4CAF50)
    private val GreenDark = Color(0xFF81C784)
    private val RedLight = Color(0xFFE53935)
    private val RedDark = Color(0xFFEF5350)
    private val LightGreenBg = Color(0xFFDFFAE4)
    private val DarkGreenBg = Color(0xFF1A3825)
    private val LightRedBg = Color(0xFFFDE8E8)
    private val DarkRedBg = Color(0xFF3A2020)
    private val Orange = Color(0xFFFFB74D)
    private val Blue = Color(0xFF69C0FF)
}

enum class RunState { Running, Stopped }
enum class ActionKind { Start, Stop, Default }
