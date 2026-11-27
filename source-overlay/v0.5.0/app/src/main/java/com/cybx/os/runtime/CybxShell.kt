package com.cybx.os.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rootless PRoot command shell. Uses Android ProcessBuilder instead of Termux JNI/PTY,
 * isolating terminal startup from the native terminal-view code path.
 *
 * It is a persistent non-PTY shell: shell variables and cd persist, while full-screen
 * terminal applications and terminal resize control are not supported in this phase.
 */
class CybxShell(private val context: Context, private val paths: CybxPaths) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null

    fun start(
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running.get()) return
        executor.execute {
            try {
                val problem = ProotAdapter(context, paths).readinessError()
                check(problem == null) { problem ?: "Kali rootless runtime is not ready" }
                paths.prootTmp.mkdirs()
                paths.rootHome.mkdirs()
                paths.sharedMemory.mkdirs()

                val command = listOf(
                    paths.prootExecutable.absolutePath,
                    "--link2symlink", "-0",
                    "-r", paths.rootfs.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "${paths.sharedMemory.absolutePath}:/dev/shm",
                    "-w", "/root",
                    "/usr/bin/env", "-i",
                    "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
                    "LANG=C.UTF-8", "TERM=xterm-256color",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "/bin/bash", "--noprofile", "--norc"
                )
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                builder.environment()["HOME"] = paths.rootHome.absolutePath
                builder.environment()["TMPDIR"] = paths.prootTmp.absolutePath
                builder.environment()["PROOT_TMP_DIR"] = paths.prootTmp.absolutePath
                val child = builder.start()
                process = child
                writer = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))
                running.set(true)
                post(onLine, "CybX rootless shell started. Commands run inside the Kali PRoot userspace.")
                post(onLine, "Note: persistent shell; full-screen TUI apps (top, nano, vim) need a PTY and are not supported yet.")
                BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        post(onLine, line)
                    }
                }
                val exitCode = child.waitFor()
                running.set(false)
                process = null
                writer = null
                post(onExit, exitCode)
            } catch (e: Exception) {
                Log.e(TAG, "PRoot shell start/read failure", e)
                running.set(false)
                process = null
                writer = null
                post(onError, "${e.javaClass.simpleName}: ${e.message ?: "Could not start the Kali shell"}")
            } catch (e: LinkageError) {
                Log.e(TAG, "PRoot executable linkage error", e)
                running.set(false)
                post(onError, "Native runtime error: ${e.message ?: "PRoot could not start"}")
            }
        }
    }

    @Synchronized
    fun send(command: String): Boolean {
        if (!running.get()) return false
        return try {
            val out = writer ?: return false
            out.write(command)
            out.newLine()
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not write to Kali shell", e)
            false
        }
    }

    fun isRunning(): Boolean = running.get() && process?.isAlive == true

    fun close() {
        try {
            writer?.apply { write("exit"); newLine(); flush() }
        } catch (_: Exception) { }
        running.set(false)
        try { process?.destroy() } catch (_: Exception) { }
        process = null
        writer = null
    }

    private fun <T> post(callback: (T) -> Unit, value: T) {
        mainHandler.post { callback(value) }
    }

    companion object {
        private const val TAG = "CybX-RootlessShell"
    }
}
