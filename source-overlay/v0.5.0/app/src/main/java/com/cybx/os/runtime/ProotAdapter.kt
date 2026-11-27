package com.cybx.os.runtime

import android.content.Context

/** Readiness checks for the bundled ARM64 PRoot binary and imported Kali rootfs. */
class ProotAdapter(private val context: Context, private val paths: CybxPaths) {
    fun isAvailable(): Boolean = paths.prootExecutable.isFile &&
        paths.prootExecutable.canExecute() && paths.prootExecutable.length() > 0

    fun readinessError(): String? {
        if (!isAvailable()) return "Bundled ARM64 PRoot is missing. Reinstall CybX Mobile."
        return paths.rootfsProblem()
    }

    fun isReady(): Boolean = readinessError() == null
}
