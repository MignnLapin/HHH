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
import android.widget.Toast

class KeyImeService : InputMethodService() {

    companion object {
        @Volatile
        var instance: KeyImeService? = null
            private set

        private const val NO_KEY = -1
        private const val RESTORE_DELAY_MS = 500L
    }

    private var heldKeyCode = NO_KEY
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 键盘收起 / 输入框失焦后切回原输入法。
     * 不能用 currentInputConnection 判断：输入结束后该引用可能仍然保留，
     * 这里改为等待键盘窗口真正隐藏（isInputViewShown == false）。
     */
    private val restoreRunnable = Runnable {
        val fixed = Prefs.getFixedApps(this)
        // 前台未知时保持现状，避免把固定应用内的切换误恢复
        val foreground = AutoSwitchService.foregroundPackage ?: return@Runnable
        val stayInFixedApp = fixed.contains(foreground)
        if (stayInFixedApp) return@Runnable
        if (isInputViewShown) return@Runnable
        if (!Prefs.isAutoSwitchEnabled(this)) return@Runnable
        if (!ImeSwitcher.isOurImeActive(this)) return@Runnable
        if (ImeSwitcher.restorePrevious(this)) {
            Toast.makeText(this, "已切回原输入法", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleRestore() {
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
    }

    /**
     * 系统要求本输入法弹出键盘 = 用户正要打字（点了输入框）。
     * 这是最直接的信号，不依赖无障碍焦点事件或 dumpsys 轮询，
     * 收到后立即切回原输入法，让用户用原来的键盘输入。
     */
    private val typingRequestedRunnable = Runnable {
        AutoSwitchService.requestTypingMode(this)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        handler.removeCallbacks(typingRequestedRunnable)
        handler.postDelayed(typingRequestedRunnable, 120L)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(typingRequestedRunnable)
        super.onFinishInputView(finishingInput)
        scheduleRestore()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        handler.removeCallbacks(restoreRunnable)
        handler.removeCallbacks(typingRequestedRunnable)
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
        handler.removeCallbacks(typingRequestedRunnable)
        super.onFinishInput()
        scheduleRestore()
    }

    override fun onWindowHidden() {
        handler.removeCallbacks(typingRequestedRunnable)
        super.onWindowHidden()
        scheduleRestore()
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
