package com.narcictub.app.ui.theme

import com.narcictub.app.domain.model.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 13 — theme resolution: the persisted mode decides the real dark/
 * light rendering, with SYSTEM following the OS setting.
 */
class ThemeResolveTest {

    @Test
    fun `system mode follows the os setting`() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(systemDark = false))
    }

    @Test
    fun `dark mode is always dark regardless of the system`() {
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemDark = false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemDark = true))
    }

    @Test
    fun `light mode is always light regardless of the system`() {
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemDark = true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemDark = false))
    }
}
