package com.cybx.os.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.nio.charset.StandardCharsets

/** Persistent PTY-backed Termux terminal emulator connected to the CybX PRoot shell. */
class CybxTerminalController(
    context: Context,
    private val paths: CybxPaths,
    private val onOutput: () -> Unit,
    private val onFinished: (Int) -> Unit,
    private val onTitle: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            mainHandler.post { terminalView?.onScreenUpdated(); onOutput() }
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            mainHandler.post { onTitle(changedSession.getTitle() ?: "root@cybx") }
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            mainHandler.post {
                if (session === finishedSession) session = null
                terminalView?.onScreenUpdated()
                onFinished(finishedSession.getExitStatus())
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CybX Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            session?.write(bytes, 0, bytes.size)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { mainHandler.post { terminalView?.onScreenUpdated() } }
        override fun onTerminalCursorStateChange(state: Boolean) {
            if (state) mainHandler.post { terminalView?.setTerminalCursorBlinkerRate(550) }
        }
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { Log.e("CybX/$tag", message) }
        override fun logWarn(tag: String, message: String) { Log.w("CybX/$tag", message) }
        override fun logInfo(tag: String, message: String) { Log.i("CybX/$tag", message) }
        override fun logDebug(tag: String, message: String) { Log.d("CybX/$tag", message) }
        override fun logVerbose(tag: String, message: String) { Log.v("CybX/$tag", message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e("CybX/$tag", message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e("CybX/$tag", "Terminal error", e) }
    }

    private fun ensureSession(): TerminalSession {
        session?.let { return it }
        val adapter = ProotAdapter(paths)
        val command = adapter.buildInteractiveCommand()
        return TerminalSession(
            command.first(), paths.base.absolutePath, command.toTypedArray(),
            adapter.terminalEnvironment(), 3000, sessionClient
        ).also { session = it }
    }

    fun attach(view: TerminalView) {
        val current = ensureSession()
        if (terminalView !== view) {
            terminalView = view
            view.setBackgroundColor(0xFF030705.toInt())
            view.setTextSize(14)
            view.setTypeface(Typeface.MONOSPACE)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setTerminalViewClient(createViewClient(view))
            view.setTerminalCursorBlinkerRate(550)
        }
        if (view.getCurrentSession() !== current) view.attachSession(current)
        view.onScreenUpdated()
    }

    private fun createViewClient(view: TerminalView) = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale.coerceIn(0.65f, 1.6f)
        override fun onSingleTapUp(e: MotionEvent) {
            view.requestFocus()
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() { view.onScreenUpdated(); view.setTerminalCursorBlinkerRate(550) }
        override fun logError(tag: String, message: String) { Log.e("CybX/$tag", message) }
        override fun logWarn(tag: String, message: String) { Log.w("CybX/$tag", message) }
        override fun logInfo(tag: String, message: String) { Log.i("CybX/$tag", message) }
        override fun logDebug(tag: String, message: String) { Log.d("CybX/$tag", message) }
        override fun logVerbose(tag: String, message: String) { Log.v("CybX/$tag", message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e("CybX/$tag", message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e("CybX/$tag", "Terminal view error", e) }
    }

    fun detach(view: TerminalView) { if (terminalView === view) terminalView = null }
    fun send(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }
    fun restart() {
        session?.finishIfRunning()
        session = null
        terminalView?.attachSession(ensureSession())
    }
    fun stop() { session?.finishIfRunning(); session = null }
}
