package com.hhh.floatingkey

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        if (!Prefs.isAutoSwitchEnabled(this)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == packageName) return
                if (pkg == "com.android.systemui") return
                if (foregroundPackage == pkg) return
                foregroundPackage = pkg
                handleForegroundChange(pkg)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
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
                    ImeSwitcher.trySwitchToOurs(this)
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
            ImeSwitcher.trySwitchToOurs(this)
        } else if (fixed.isNotEmpty()) {
            // 严格按前台应用判断：配置了固定应用时，不在列表里的前台应用一律切回原输入法
            ImeSwitcher.restorePrevious(this)
        }
    }
}
