package com.example

import com.example.update.LegacyInstall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old package is only worth an uninstall screen while it is installed and
 * this version has not already asked for it.
 */
class LegacyInstallPolicyTest {

    @Test
    fun `a leftover install is removed on the first launch of a version`() {
        assertTrue(
            LegacyInstall.shouldRemove(
                isInstalled = true,
                askedVersionCode = -1,
                currentVersionCode = 29
            )
        )
    }

    @Test
    fun `a declined removal is not asked for again in the same version`() {
        assertFalse(
            LegacyInstall.shouldRemove(
                isInstalled = true,
                askedVersionCode = 29,
                currentVersionCode = 29
            )
        )
    }

    @Test
    fun `the next version asks once more while the old app is still there`() {
        assertTrue(
            LegacyInstall.shouldRemove(
                isInstalled = true,
                askedVersionCode = 29,
                currentVersionCode = 30
            )
        )
    }

    @Test
    fun `nothing happens when the old app is not installed`() {
        assertFalse(
            LegacyInstall.shouldRemove(
                isInstalled = false,
                askedVersionCode = -1,
                currentVersionCode = 29
            )
        )
    }
}
