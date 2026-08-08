package com.nothing.camera2magic.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStatusTest {

    @Test
    fun enabledWhenXposedActiveAndMasterSwitchOn() {
        assertEquals(ModuleStatus.Enabled, moduleStatus(xposedActive = true, masterSwitchEnabled = true))
    }

    @Test
    fun inactiveWhenXposedNotActiveRegardlessOfMasterSwitch() {
        assertEquals(ModuleStatus.Inactive, moduleStatus(xposedActive = false, masterSwitchEnabled = true))
        assertEquals(ModuleStatus.Inactive, moduleStatus(xposedActive = false, masterSwitchEnabled = false))
    }

    @Test
    fun disabledWhenXposedActiveButMasterSwitchOff() {
        assertEquals(ModuleStatus.Disabled, moduleStatus(xposedActive = true, masterSwitchEnabled = false))
    }
}
