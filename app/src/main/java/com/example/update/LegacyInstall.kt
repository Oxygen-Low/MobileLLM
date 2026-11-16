package com.example.update

/**
 * The package id the app was published under before V28.
 *
 * V28 moved the app to a different id, which Android treats as a different
 * app: installing it over the old one does not update the old install but adds
 * a second copy next to it. The old package keeps its private storage, and
 * with it every model it downloaded, until it is uninstalled. That leftover is
 * the only reason this class exists.
 */
object LegacyInstall {

    const val PACKAGE_NAME = "com.aistudio.pocketlm.krfxwq"

    /**
     * Whether the uninstall screen should be opened right now.
     *
     * [askedVersionCode] is the version that already opened it, so a user who
     * declined is not asked again on every launch. The next release asks once
     * more while the old package is still installed.
     */
    fun shouldRemove(
        isInstalled: Boolean,
        askedVersionCode: Int,
        currentVersionCode: Int
    ): Boolean = isInstalled && askedVersionCode != currentVersionCode
}
