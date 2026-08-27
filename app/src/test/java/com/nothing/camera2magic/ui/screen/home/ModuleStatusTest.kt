package com.nothing.camera2magic.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStatusTest {

    @Test
    fun enabledWhenXposedActive() {
        assertEquals(ModuleStatus.Enabled, moduleStatus(xposedActive = true))
    }

    @Test
    fun inactiveWhenXposedNotActive() {
        assertEquals(ModuleStatus.Inactive, moduleStatus(xposedActive = false))
    }
}
