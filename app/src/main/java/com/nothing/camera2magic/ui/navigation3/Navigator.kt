package com.nothing.camera2magic.ui.navigation3

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavKey

import android.util.Log

class Navigator(val backStack: MutableList<NavKey>) {
    fun push(key: NavKey) {
        Log.d("NavDebug", "push: $key, stack=${backStack.size + 1}")
        backStack.add(key)
    }

    fun pop() {
        Log.d("NavDebug", "pop before, stack=${backStack.size}")
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        Log.d("NavDebug", "pop after, stack=${backStack.size}")
    }

    fun current() = backStack.lastOrNull()

    fun backStackSize() = backStack.size
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
