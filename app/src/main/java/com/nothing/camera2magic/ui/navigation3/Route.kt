package com.nothing.camera2magic.ui.navigation3

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object ThemeSettings : Route

    @Serializable
    data object About : Route

    @Serializable
    data class AppConfig(val packageName: String) : Route
}
