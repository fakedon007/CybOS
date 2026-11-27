package com.cybx.os.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Owns a single PTY session and guards native PTY creation against uncaught startup exceptions. */
class CybxTerminalHost(
    private val context: Context,
    private val paths: CybxPaths
) {
    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null
    private var pendingView: TerminalView? = null
    private var pendingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private fun logFailure(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, "$tag: $message", error) else Log.e(TAG, "$tag: $message")
        errorMessage = "$tag: $message"
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView?.postInvalidate() }
        override fun onTitleChanged(changedSession: TerminalSession) { terminalView?.postInvalidate() }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            terminalView?.postInvalidate()
            if (finishedSession === session && finishedSession.exitStatus != 0) {
                logFailure("PTY", "Kali shell exited with status ${finishedSession.exitStatus}; check PRoot and rootfs.")
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("CybX Terminal", text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) { terminalView?.postInvalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { terminalView?.postInvalidate() }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = logFailure(tag, message)
        override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: $message") }
        override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: $message") }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = logFailure(tag, message, e)
        override fun logStackTrace(tag: String, e: Exception) = logFailure(tag, e.message ?: "See logcat", e)
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {
            terminalView?.let { view ->
                view.requestFocus()
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() { terminalView?.requestFocus() }
        override fun logError(tag: String, message: String) = logFailure(tag, message)
        override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: $message") }
        override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: $message") }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = logFailure(tag, message, e)
        override fun logStackTrace(tag: String, e: Exception) = logFailure(tag, e.message ?: "See logcat", e)
    }

    /** Wait until Android has measured the view; attachSession() then creates the PTY inside try/catch. */
    fun attach(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(viewClient)
        view.setBackgroundColor(Color.rgb(3, 7, 5))
        view.setTypeface(Typeface.MONOSPACE)
        view.setTextSize(15)
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        if (view.width <= 0 || view.height <= 0) {
            if (pendingView === view && pendingLayoutListener != null) return
            clearPendingListener()
            pendingView = view
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (view.width <= 0 || view.height <= 0) return
                    clearPendingListener()
                    attachMeasuredView(view)
                }
            }
            pendingLayoutListener = listener
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } else {
            clearPendingListener()
            attachMeasuredView(view)
        }
    }

    private fun clearPendingListener() {
        val v = pendingView
        val listener = pendingLayoutListener
        if (v != null && listener != null && v.viewTreeObserver.isAlive) {
            v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        pendingView = null
        pendingLayoutListener = null
    }

    private fun attachMeasuredView(view: TerminalView) {
        try {
            val activeSession = session?.takeIf { it.isRunning() } ?: run {
                session?.finishIfRunning()
                ProotAdapter(context, paths).createTerminalSession(sessionClient).also { session = it }
            }
            if (view.currentSession !== activeSession) view.attachSession(activeSession)
            view.requestFocus()
            errorMessage = null
        } catch (e: Exception) {
            failStartup(e)
        } catch (e: LinkageError) {
            failStartup(e)
        }
    }

    private fun failStartup(error: Throwable) {
        Log.e(TAG, "Failed to create or attach CybX PTY session", error)
        session?.finishIfRunning()
        session = null
        errorMessage = "${error.javaClass.simpleName}: ${error.message ?: "PTY startup failed; see logcat"}"
        Toast.makeText(context, "CybX Terminal: $errorMessage", Toast.LENGTH_LONG).show()
    }

    fun send(text: String) {
        val active = session ?: return
        if (!active.isRunning()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        active.write(bytes, 0, bytes.size)
    }

    fun restart() {
        errorMessage = null
        session?.finishIfRunning()
        session = null
        terminalView?.let(::attach)
    }

    fun close() {
        clearPendingListener()
        session?.finishIfRunning()
        session = null
        terminalView = null
    }

    companion object {
        private const val TAG = "CybX-Terminal"
    }
}
