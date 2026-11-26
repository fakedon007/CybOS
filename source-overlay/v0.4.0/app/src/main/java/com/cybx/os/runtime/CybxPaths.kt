package com.cybx.os.runtime

import android.content.Context
import java.io.File

data class RootfsStatus(
    val installed: Boolean,
    val prettyName: String = "",
    val reason: String = "Linux rootfs not installed"
)

class CybxPaths(context: Context) {
    val base = File(context.applicationContext.filesDir, "cybx").apply { mkdirs() }
    // Android extracts APK-packaged native libraries into the protected /data/app path.
    val prootExecutable = File(context.applicationContext.applicationInfo.nativeLibraryDir, "libproot.so")
    val rootfs = File(base, "rootfs")
    val downloads = File(base, "downloads").apply { mkdirs() }
    val prootTmp = File(base, "proot-tmp").apply { if (!exists()) mkdirs() }
    val rootHome = File(base, "home-root").apply { mkdirs() }
    val rootfsMarker = File(base, "rootfs-installed.txt")

    fun rootfsStatus(): RootfsStatus {
        if (!rootfs.isDirectory) return RootfsStatus(false, reason = "Rootfs directory is missing")
        val release = File(rootfs, "etc/os-release")
        if (!release.isFile) return RootfsStatus(false, reason = "Rootfs is incomplete: etc/os-release is missing")
        if (listOf(File(rootfs, "usr/bin/env"), File(rootfs, "bin/env")).none { it.isFile })
            return RootfsStatus(false, reason = "Rootfs is incomplete: usr/bin/env is missing")
        if (listOf(File(rootfs, "usr/bin/bash"), File(rootfs, "bin/bash")).none { it.isFile })
            return RootfsStatus(false, reason = "Rootfs is incomplete: bash is missing")
        val osRelease = runCatching { release.readText() }.getOrDefault("")
        if (!osRelease.contains("ID=kali") && !osRelease.contains("Kali"))
            return RootfsStatus(false, reason = "Installed rootfs is not identified as Kali Linux")
        val name = osRelease.lineSequence().firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim('"') ?: "Kali Linux ARM64"
        return RootfsStatus(true, name, "Ready")
    }
}
