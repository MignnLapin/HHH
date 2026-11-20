package com.hhh.floatingkey

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 监听前台应用与输入框焦点，自动切换输入法：
 * - 打开固定应用时切到「悬浮按键输入法」，离开后切回原输入法
 * - 焦点进入可编辑输入框时切到「悬浮按键输入法」
 */
class AutoSwitchService : AccessibilityService() {

    companion object {
        @Volatile
        var foregroundPackage: String? = null
            private set

        fun setForeground(pkg: String?) {
            foregroundPackage = pkg
            OverlayService.onForegroundChanged(pkg)
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AutoSwitchService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        foregroundPackage = null
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == packageName) return
                if (pkg == "com.android.systemui") return
                if (foregroundPackage == pkg) return
                // 走 setForeground，通知悬浮窗同步显示/隐藏（悬浮窗不受自动切换开关影响）
                setForeground(pkg)
                if (Prefs.isAutoSwitchEnabled(this)) {
                    handleForegroundChange(pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (!Prefs.isAutoSwitchEnabled(this)) return
                if (!Prefs.isAutoSwitchOnFocus(this)) return
                // 配置了固定应用后，只在固定应用内响应输入框焦点，其他应用保持原输入法
                val fixed = Prefs.getFixedApps(this)
                if (fixed.isNotEmpty()) {
                    val foreground = foregroundPackage ?: return
                    if (!fixed.contains(foreground)) return
                }
                val source: AccessibilityNodeInfo = event.source ?: return
                try {
                    if (!source.isEditable) return
                    if (source.isPassword) return
                    if (ImeSwitcher.isOurImeActive(this)) return
                    if (ImeSwitcher.trySwitchToOurs(this) == SwitchResult.OK) {
                        Toast.makeText(this, "已切换到悬浮按键输入法", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    source.recycle()
                }
            }
        }
    }

    private fun handleForegroundChange(pkg: String) {
        val fixed = Prefs.getFixedApps(this)
        if (fixed.contains(pkg)) {
            val wasActive = ImeSwitcher.isOurImeActive(this)
            val result = ImeSwitcher.trySwitchToOurs(this)
            when {
                result == SwitchResult.OK && !wasActive ->
                    Toast.makeText(this, "已切换到悬浮按键输入法", Toast.LENGTH_SHORT).show()
                result != SwitchResult.OK ->
                    Toast.makeText(this, "切换输入法失败：${result.message}", Toast.LENGTH_LONG).show()
            }
        } else if (fixed.isNotEmpty()) {
            // 严格按前台应用判断：配置了固定应用时，不在列表里的前台应用一律切回原输入法
            if (ImeSwitcher.isOurImeActive(this) && ImeSwitcher.restorePrevious(this)) {
                Toast.makeText(this, "已切回原输入法", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
