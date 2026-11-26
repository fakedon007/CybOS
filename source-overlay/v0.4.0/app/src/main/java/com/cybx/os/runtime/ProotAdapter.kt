package com.cybx.os.runtime

/** Builds the host-side command used to start the Android ARM64 PRoot executable. */
class ProotAdapter(private val paths: CybxPaths) {
    private val executable = paths.prootExecutable
    fun isAvailable(): Boolean = executable.isFile && executable.canExecute() && executable.length() > 0L

    fun buildInteractiveCommand(): List<String> {
        require(isAvailable()) { "Bundled Android ARM64 PRoot is missing. Reinstall CybX Mobile." }
        val status = paths.rootfsStatus()
        require(status.installed) { status.reason }
        ensureWritableRuntimeDirs()
        return listOf(
            executable.absolutePath, "--link2symlink", "-0",
            "-r", paths.rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${paths.rootHome.absolutePath}:/root",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root",
            "LANG=C.UTF-8", "TERM=xterm-256color", "COLORTERM=truecolor",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "--login"
        )
    }

    fun terminalEnvironment(): Array<String> {
        ensureWritableRuntimeDirs()
        return arrayOf(
            "HOME=${paths.rootHome.absolutePath}",
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "TMPDIR=${paths.prootTmp.absolutePath}",
            "PROOT_TMP_DIR=${paths.prootTmp.absolutePath}",
            "TERM=xterm-256color"
        )
    }

    private fun ensureWritableRuntimeDirs() {
        listOf(paths.prootTmp, paths.rootHome).forEach { dir ->
            if (!dir.exists()) check(dir.mkdirs() || dir.isDirectory) { "Cannot create runtime directory: ${dir.absolutePath}" }
            check(dir.isDirectory && dir.canWrite()) { "Runtime directory is not writable: ${dir.absolutePath}" }
        }
    }
}
