package com.hhh.floatingkey

import android.inputmethodservice.InputMethodService
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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
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

    /** 模拟实体键盘发送按下/抬起按键事件到当前输入框。 */
    fun sendKey(keyCode: Int): Boolean {
        val connection = currentInputConnection ?: return false
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        val up = KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0)
        val downSent = connection.sendKeyEvent(down)
        val upSent = connection.sendKeyEvent(up)
        return downSent && upSent
    }
}
