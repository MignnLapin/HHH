package com.hhh.floatingkey

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 监听前台应用与输入框焦点，自动切换输入法：
 * - 打开固定应用时切到「悬浮按键输入法」，离开后切回原输入法
 * - 焦点进入可编辑输入框时切到「悬浮按键输入法」
 *
 * 前台判定统一走 [notifyWindow] 去抖：切换输入法时旧键盘窗口关闭、
 * 窗口动画等会抛出大量瞬时窗口事件，只有同一个包名稳定一段时间后才
 * 认定为真正的前台变化，避免反复切换 / 反复恢复。
 */
class AutoSwitchService : AccessibilityService() {

    companion object {
        /** 同一个前台包名持续稳定这么久才确认，用来滤掉窗口动画与输入法窗口事件。 */
        private const val FOREGROUND_STABLE_MS = 600L

        @Volatile
        var foregroundPackage: String? = null
            private set

        private val mainHandler = Handler(Looper.getMainLooper())

        /** 正在等待确认的前台包名。 */
        private var pendingPackage: String? = null
        private var pendingRunnable: Runnable? = null

        /** 上一次写日志的忽略包名，避免重复事件刷屏。 */
        private var lastIgnoredPackage: String? = null

        /**
         * 所有前台来源（无障碍窗口事件、Root / Shizuku 轮询）都调用这里。
         * 同一个包名稳定 [FOREGROUND_STABLE_MS] 后才算真正的前台变化。
         */
        fun notifyWindow(context: Context, rawPkg: String?, source: String) {
            if (rawPkg.isNullOrBlank()) return
            val app = context.applicationContext
            val pkg = rawPkg

            val ignoreReason = when {
                pkg == app.packageName -> "本应用"
                pkg == "com.android.systemui" -> "系统界面"
                ImeSwitcher.isImePackage(app, pkg) -> "输入法窗口"
                else -> null
            }
            if (ignoreReason != null) {
                if (pkg != foregroundPackage && pkg != pendingPackage &&
                    pkg != lastIgnoredPackage
                ) {
                    lastIgnoredPackage = pkg
                    AppLog.log(app, "fg", "忽略前台=$pkg（$ignoreReason，来源=$source）")
                }
                return
            }

            if (pkg == foregroundPackage) return
            if (pkg == pendingPackage) return

            pendingPackage = pkg
            pendingRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (pendingPackage != pkg) return@Runnable
                pendingPackage = null
                commitForeground(app, pkg, source)
            }
            pendingRunnable = runnable
            mainHandler.postDelayed(runnable, FOREGROUND_STABLE_MS)
        }

        private fun commitForeground(context: Context, pkg: String, source: String) {
            if (pkg == foregroundPackage) return
            val previous = foregroundPackage
            foregroundPackage = pkg
            lastIgnoredPackage = null
            // 悬浮窗不受自动切换开关影响，先同步显隐
            OverlayService.onForegroundChanged(pkg)
            AppLog.log(context, "fg", "前台=$pkg（来源=$source，上次=$previous）")

            if (!Prefs.isAutoSwitchEnabled(context)) return
            val fixed = Prefs.getFixedApps(context)
            when {
                fixed.contains(pkg) -> switchToOurs(context)
                fixed.isNotEmpty() && ImeSwitcher.isOurImeActive(context) -> restorePrevious(context)
            }
        }

        private fun switchToOurs(context: Context) {
            val result = ImeSwitcher.trySwitchToOurs(context)
            when (result) {
                SwitchResult.OK ->
                    toast(context, "已切换到悬浮按键输入法")
                SwitchResult.NOTHING, SwitchResult.AUTO_OFF -> Unit
                else -> toast(context, "切换输入法失败：${result.message}")
            }
        }

        private fun restorePrevious(context: Context) {
            if (ImeSwitcher.restorePrevious(context)) {
                toast(context, "已切回原输入法")
            }
        }

        private fun toast(context: Context, text: String) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
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
        pendingPackage = null
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 交给去抖器：只有稳定停留的包名才会被认定为前台
                notifyWindow(this, pkg, "无障碍")
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                notifyWindow(this, pkg, "焦点")
                handleFocus(pkg, event.source)
            }
        }
    }

    private fun handleFocus(pkg: String, source: AccessibilityNodeInfo?) {
        if (!Prefs.isAutoSwitchEnabled(this)) return
        if (!Prefs.isAutoSwitchOnFocus(this)) return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        if (ImeSwitcher.isImePackage(this, pkg)) return
        // 配置了固定应用后，只在固定应用内响应输入框焦点
        val fixed = Prefs.getFixedApps(this)
        if (fixed.isNotEmpty()) {
            val foreground = foregroundPackage ?: return
            if (!fixed.contains(foreground)) return
        }
        if (ImeSwitcher.isOurImeActive(this)) return
        if (source == null) return
        try {
            if (!source.isEditable) return
            if (source.isPassword) return
            if (ImeSwitcher.trySwitchToOurs(this) == SwitchResult.OK) {
                Toast.makeText(this, "已切换到悬浮按键输入法", Toast.LENGTH_SHORT).show()
            }
        } finally {
            @Suppress("DEPRECATION")
            source.recycle()
        }
    }
}
