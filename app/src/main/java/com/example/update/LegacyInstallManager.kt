package com.example.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Finds the app that was installed under the old package id and asks the
 * system to remove it.
 *
 * Android does not let one app uninstall another silently - only a device
 * owner may do that. [Intent.ACTION_DELETE] opens the uninstall confirmation
 * the user already knows, and the package manager removes the app and its
 * private files, including the downloaded models, once it is confirmed.
 */
class LegacyInstallManager(private val context: Context) {

    fun isPreviousVersionInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(LegacyInstall.PACKAGE_NAME, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Opens the system uninstall screen for the old package.
     *
     * @return true when the screen was opened, false when the device has no
     * activity that handles the request.
     */
    fun requestUninstall(): Boolean = try {
        context.startActivity(uninstallIntent())
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // Some vendor builds restrict who may start the uninstall activity.
        false
    }

    /** The uninstall request for the old package. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE).apply {
        data = Uri.parse("package:${LegacyInstall.PACKAGE_NAME}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
