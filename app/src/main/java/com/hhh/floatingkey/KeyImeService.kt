package com.hhh.floatingkey

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class KeyImeService : InputMethodService() {

    companion object {
        @Volatile
        var instance: KeyImeService? = null
            private set

        private const val NO_KEY = -1
    }

    private var heldKeyCode = NO_KEY
    private val handler = Handler(Looper.getMainLooper())
    private val restoreRunnable = Runnable {
        val foreground = AutoSwitchService.foregroundPackage
        val stayInFixedApp = foreground != null &&
            Prefs.getFixedApps(this).contains(foreground)
        if (currentInputConnection == null && !stayInFixedApp) {
            ImeSwitcher.restorePrevious(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        handler.removeCallbacks(restoreRunnable)
        releaseHeldIfAny()
        instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val hint = TextView(this).apply {
            text = "悬浮按键输入法已就绪"
            textSize = 13f
            gravity = Gravity.CENTER
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        return FrameLayout(this).apply {
            addView(
                hint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        handler.removeCallbacks(restoreRunnable)
        super.onStartInput(attribute, restarting)
    }

    override fun onFinishInput() {
        releaseHeldIfAny()
        super.onFinishInput()
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, 400)
    }

    /** 单次按键：发送完整的按下 + 抬起。 */
    fun sendKey(keyCode: Int): Boolean {
        val connection = currentInputConnection ?: return false
        releaseHeldIfAny()
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        val up = KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0)
        val downSent = connection.sendKeyEvent(down)
        val upSent = connection.sendKeyEvent(up)
        return downSent && upSent
    }

    /** 按住模式：只发送按下，保持按住状态。 */
    fun pressKeyDown(keyCode: Int): Boolean {
        val connection = currentInputConnection ?: return false
        releaseHeldIfAny()
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        val sent = connection.sendKeyEvent(down)
        if (sent) heldKeyCode = keyCode
        return sent
    }

    /** 按住模式：发送抬起，结束按住状态。 */
    fun releaseKey(): Boolean {
        if (heldKeyCode == NO_KEY) return true
        val connection = currentInputConnection
        val code = heldKeyCode
        heldKeyCode = NO_KEY
        if (connection == null) return false
        val time = SystemClock.uptimeMillis()
        return connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, 0))
    }

    fun isHolding(): Boolean = heldKeyCode != NO_KEY

    private fun releaseHeldIfAny() {
        if (heldKeyCode == NO_KEY) return
        val connection = currentInputConnection
        val code = heldKeyCode
        heldKeyCode = NO_KEY
        if (connection == null) return
        val time = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, 0))
    }
}
