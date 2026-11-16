package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.LegacyInstallPreferences
import com.example.update.LegacyInstall
import com.example.update.LegacyInstallManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The old package has to be found even though it is a different app, and the
 * uninstall request has to point at that package and not at this one.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyInstallManagerTest {

    private lateinit var context: Context
    private lateinit var manager: LegacyInstallManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = LegacyInstallManager(context)
    }

    @Test
    fun `the old package is not reported on a clean install`() {
        assertFalse(manager.isPreviousVersionInstalled())
    }

    @Test
    fun `the old package is detected when it is installed`() {
        installPreviousVersion()

        assertTrue(manager.isPreviousVersionInstalled())
    }

    @Test
    fun `the uninstall request targets the old package`() {
        val intent = manager.uninstallIntent()

        assertEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals(Uri.parse("package:${LegacyInstall.PACKAGE_NAME}"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the version that asked is remembered across launches`() {
        // A fresh set of preferences stands for the next launch of the app.
        assertEquals(
            LegacyInstallPreferences.NEVER_ASKED,
            LegacyInstallPreferences(context).askedVersionCode
        )

        LegacyInstallPreferences(context).askedVersionCode = 29

        assertEquals(29, LegacyInstallPreferences(context).askedVersionCode)
    }

    private fun installPreviousVersion() {
        val info = PackageInfo().apply {
            packageName = LegacyInstall.PACKAGE_NAME
        }
        shadowOf(context.packageManager).installPackage(info)
    }
}
