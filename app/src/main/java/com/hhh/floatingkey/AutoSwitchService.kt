package com.hhh.floatingkey

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 监听前台应用与输入框焦点，自动切换输入法：
 * - 打开固定应用时切到「悬浮按键输入法」，离开后切回原输入法
 * - 固定应用内输入法被切走时自动切回
 * - 未配置固定应用时，焦点进入可编辑输入框才切换
 *
 * 前台判定统一走 [notifyWindow] 去抖：切换输入法时旧键盘窗口关闭、
 * 窗口动画、MIUI 安全中心 / 系统插件弹窗等瞬时窗口都会被过滤，
 * 只有同一个包名稳定一段时间后才认定为真正的前台变化。
 */
class AutoSwitchService : AccessibilityService() {

    companion object {
        /** 同一个前台包名持续稳定这么久才确认，用来滤掉窗口动画与各类系统弹窗。 */
        private const val FOREGROUND_STABLE_MS = 600L

        /** 离开固定应用后延迟这么久再恢复，期间又回到固定应用就取消恢复。 */
        private const val RESTORE_SETTLE_MS = 900L

        /** 固定应用内输入法被切走后，稍等片刻再切回。 */
        private const val IME_ENFORCE_DELAY_MS = 800L

        /** 不是“用户应用”的系统窗口，不能作为前台应用参与切换/恢复判定。 */
        private val systemWindowPackages = setOf(
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.miui.securitycenter",
            "com.miui.aod",
            "com.miui.powerkeeper",
            "com.miui.contentextension",
            "com.miui.voiceassist",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.lbe.security.miui"
        )

        @Volatile
        var foregroundPackage: String? = null
            private set

        /** 是否处于“用户正在输入”状态：固定应用内键盘弹出时切回原输入法。 */
        @Volatile
        var typingMode = false
            private set

        /** 切回本输入法后的一小段时间内，不再因“键盘弹出”而切走，避免来回循环。 */
        private var ignoreKeyboardEnterUntil = 0L

        private val mainHandler = Handler(Looper.getMainLooper())

        /** 正在等待确认的前台包名。 */
        private var pendingPackage: String? = null
        private var pendingRunnable: Runnable? = null

        /** 上一次写日志的忽略包名，避免重复事件刷屏。 */
        private var lastIgnoredPackage: String? = null

        /** 每次确认前台自增，用来作废过期的“延迟恢复”。 */
        private var commitGeneration = 0

        private fun ignoreReason(context: Context, pkg: String): String? = when {
            pkg == context.packageName -> "本应用"
            pkg in systemWindowPackages || pkg.startsWith("miui.systemui") -> "系统窗口"
            ImeSwitcher.isImePackage(context, pkg) -> "输入法窗口"
            else -> null
        }

        /**
         * 所有前台来源（无障碍窗口事件、Root / Shizuku 轮询）都调用这里。
         * 同一个包名稳定 [FOREGROUND_STABLE_MS] 后才算真正的前台变化。
         */
        fun notifyWindow(context: Context, rawPkg: String?, source: String) {
            if (rawPkg.isNullOrBlank()) return
            val app = context.applicationContext
            val pkg = rawPkg

            val reason = ignoreReason(app, pkg)
            if (reason != null) {
                if (pkg != foregroundPackage && pkg != pendingPackage &&
                    pkg != lastIgnoredPackage
                ) {
                    lastIgnoredPackage = pkg
                    AppLog.log(app, "fg", "忽略前台=$pkg（$reason，来源=$source）")
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

        /**
         * 轮询兜底：前台已在固定应用内时，确保默认输入法是本输入法。
         * 用户手动切走输入法、或系统因弹窗临时切走时，这里会切回来。
         */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            if (!Prefs.isAutoSwitchEnabled(app)) return
            // 正在输入时保持原输入法，不要抢回来
            if (typingMode) return
            val foreground = foregroundPackage ?: return
            if (!Prefs.getFixedApps(app).contains(foreground)) return
            if (ImeSwitcher.isOurImeActive(app)) return
            if (ImeSwitcher.trySwitchToOurs(app) == SwitchResult.OK) {
                AppLog.log(app, "ime", "已切换到悬浮按键输入法（轮询校正）")
            }
        }

        /**
         * 键盘状态变化（由轮询上报）：
         * - 固定应用内键盘弹出且当前是本输入法 -> 说明用户要打字，切回原输入法
         * - 键盘收起 -> 切回本输入法，供悬浮按钮发送按键
         */
        fun onKeyboardStateChanged(context: Context, keyboardShown: Boolean) {
            val app = context.applicationContext
            if (!Prefs.isAutoSwitchEnabled(app)) return
            val foreground = foregroundPackage
            if (foreground == null || !Prefs.getFixedApps(app).contains(foreground)) {
                typingMode = false
                return
            }
            if (!Prefs.isAutoSwitchOnFocus(app)) return
            if (keyboardShown) {
                if (SystemClock.elapsedRealtime() < ignoreKeyboardEnterUntil) return
                if (!typingMode && !ImeSwitcher.isOurImeActive(app)) {
                    // 键盘由别的输入法弹出：说明用户手动切了过去要打字，先别抢回来
                    typingMode = true
                    AppLog.log(app, "ime", "键盘由其他输入法弹出，暂停抢回（前台=$foreground）")
                    return
                }
                if (!typingMode && ImeSwitcher.isOurImeActive(app)) {
                    typingMode = true
                    AppLog.log(app, "ime", "键盘弹出，切回原输入法（前台=$foreground）")
                    ImeSwitcher.restorePrevious(app)
                }
            } else if (typingMode) {
                typingMode = false
                ignoreKeyboardEnterUntil = SystemClock.elapsedRealtime() + 2500L
                AppLog.log(app, "ime", "键盘收起，切回悬浮按键输入法（前台=$foreground）")
                if (!ImeSwitcher.isOurImeActive(app)) {
                    ImeSwitcher.trySwitchToOurs(app)
                }
            }
        }

        /**
         * 用户要打字时切回原输入法。
         * 由输入框焦点、键盘弹出、以及本输入法被请求弹出键盘三种信号触发，
         * 任意一种先到就生效，其余会被 typingMode 忽略。
         */
        fun requestTypingMode(context: Context) {
            val app = context.applicationContext
            if (!Prefs.isAutoSwitchEnabled(app)) return
            if (!Prefs.isAutoSwitchOnFocus(app)) return
            if (SystemClock.elapsedRealtime() < ignoreKeyboardEnterUntil) return
            val foreground = foregroundPackage ?: return
            if (!Prefs.getFixedApps(app).contains(foreground)) return
            if (typingMode) return
            if (!ImeSwitcher.isOurImeActive(app)) return
            typingMode = true
            AppLog.log(app, "ime", "需要输入，切回原输入法（前台=$foreground）")
            ImeSwitcher.restorePrevious(app)
        }

        private fun commitForeground(context: Context, pkg: String, source: String) {
            if (pkg == foregroundPackage) return
            val previous = foregroundPackage
            foregroundPackage = pkg
            lastIgnoredPackage = null
            commitGeneration++
            val generation = commitGeneration
            typingMode = false

            // 悬浮窗不受自动切换开关影响，先同步显隐
            OverlayService.onForegroundChanged(pkg)
            AppLog.log(context, "fg", "前台=$pkg（来源=$source，上次=$previous）")

            if (!Prefs.isAutoSwitchEnabled(context)) return
            val fixed = Prefs.getFixedApps(context)
            if (fixed.contains(pkg)) {
                switchToOurs(context)
                // 悬浮按钮服务被系统回收时，有权限就顺带拉起来
                if (Prefs.isOverlayEnabled(context) && !OverlayService.running) {
                    OverlayService.start(context)
                }
                return
            }
            if (fixed.isNotEmpty() && ImeSwitcher.isOurImeActive(context)) {
                // 离开固定应用：稍等一会再恢复，避免切到桌面/最近任务时来回切
                mainHandler.postDelayed({
                    if (commitGeneration == generation && foregroundPackage == pkg) {
                        restorePrevious(context)
                    }
                }, RESTORE_SETTLE_MS)
            }
        }

        private fun switchToOurs(context: Context) {
            ImeSwitcher.trySwitchToOurs(context)
        }

        private fun restorePrevious(context: Context) {
            ImeSwitcher.restorePrevious(context)
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

    private var imeObserver: ContentObserver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        pendingPackage = null
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        registerImeObserver()
        // 开机 / 更新后无障碍服务被系统拉起时，顺带恢复悬浮按钮
        if (Prefs.isOverlayEnabled(this) && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    override fun onDestroy() {
        unregisterImeObserver()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /**
     * 默认输入法被切走时（用户手动切换、系统弹窗导致）立即切回本输入法。
     * 这是「点击输入框后没有自动切回来」的兜底：不再依赖输入框焦点事件。
     */
    private fun registerImeObserver() {
        if (imeObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.removeCallbacks(enforceImeRunnable)
                mainHandler.postDelayed(enforceImeRunnable, IME_ENFORCE_DELAY_MS)
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                observer
            )
            imeObserver = observer
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun unregisterImeObserver() {
        mainHandler.removeCallbacks(enforceImeRunnable)
        imeObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (t: Throwable) {
                // ignore
            }
        }
        imeObserver = null
    }

    private val enforceImeRunnable = Runnable {
        if (!Prefs.isAutoSwitchEnabled(this)) return@Runnable
        // 用户正在输入时保持原输入法
        if (typingMode) return@Runnable
        if (ImeSwitcher.isOurImeActive(this)) return@Runnable
        val foreground = foregroundPackage ?: return@Runnable
        if (!Prefs.getFixedApps(this).contains(foreground)) return@Runnable
        AppLog.log(this, "ime", "固定应用内输入法被切走，自动切回（前台=$foreground）")
        ImeSwitcher.trySwitchToOurs(this)
    }

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
        if (ignoreReason(this, pkg) != null) return

        val fixed = Prefs.getFixedApps(this)
        if (fixed.isNotEmpty()) {
            // 固定应用内：输入框获得焦点说明用户要打字，切回原输入法
            val foreground = foregroundPackage
            if (foreground == null || !fixed.contains(foreground)) return
            val node = source ?: return
            try {
                if (!node.isEditable) return
                requestTypingMode(this)
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            return
        }

        // 未配置固定应用：只在可编辑输入框获得焦点时切换
        if (ImeSwitcher.isOurImeActive(this)) return
        val node = source ?: return
        try {
            if (!node.isEditable) return
            if (node.isPassword) return
            ImeSwitcher.trySwitchToOurs(this)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}
