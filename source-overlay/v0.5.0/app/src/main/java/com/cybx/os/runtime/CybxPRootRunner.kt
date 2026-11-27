package com.cybx.os.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Executes a fixed executable + argument list inside the existing Kali PRoot rootfs.
 * No command string is interpreted by a host shell; callers must supply validated argv.
 */
class CybxPRootRunner(
    private val context: Context,
    private val paths: CybxPaths
) {
    suspend fun run(
        argv: List<String>,
        timeoutSeconds: Long = 60,
        maxOutputChars: Int = 24_000
    ): String = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty() && argv.all { it.isNotBlank() }) { "Empty Kali command." }
        require(timeoutSeconds in 1..300) { "Invalid command timeout." }
        val problem = ProotAdapter(context, paths).readinessError()
        check(problem == null) { problem ?: "Kali rootless runtime is not ready." }

        paths.prootTmp.mkdirs()
        paths.rootHome.mkdirs()
        paths.sharedMemory.mkdirs()

        val command = mutableListOf(
            paths.prootExecutable.absolutePath,
            "--link2symlink", "-0",
            "-r", paths.rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "\${paths.sharedMemory.absolutePath}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "LANG=C.UTF-8", "TERM=xterm",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        command.addAll(argv)

        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["HOME"] = paths.rootHome.absolutePath
        builder.environment()["TMPDIR"] = paths.prootTmp.absolutePath
        builder.environment()["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath

        val process = builder.start()
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length < maxOutputChars) {
                        val allowed = minOf(count, maxOutputChars - result.length)
                        result.append(buffer, 0, allowed)
                    }
                }
                result.toString()
            }
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                runCatching { outputFuture.get(2, TimeUnit.SECONDS) }
                error("Kali command timed out after $timeoutSeconds seconds.")
            }
            val output = outputFuture.get(3, TimeUnit.SECONDS)
            "Exit code: \${process.exitValue()}\n\${output.ifBlank { "(no output)" }}"
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
