package com.hhh.floatingkey

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.hhh.floatingkey.REFRESH"

        @Volatile
        var running = false
            private set

        @Volatile
        private var instance: OverlayService? = null

        /** 前台应用变化时同步悬浮按钮的显示/隐藏。 */
        fun onForegroundChanged(pkg: String?) {
            val service = instance ?: return
            service.mainHandler.post { service.applyVisibility(pkg) }
        }

        fun start(context: Context) {
            try {
                // 普通后台服务：不再显示常驻通知
                context.startService(Intent(context, OverlayService::class.java))
            } catch (t: Throwable) {
                AppLog.log(context, "overlay", "启动悬浮按钮失败：${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            if (running) {
                try {
                    context.startService(
                        Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
                    )
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var buttonView: TextView? = null
    private var held = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadExecutor()
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!shouldPoll()) {
                polling = false
                return
            }
            pollOnce()
            mainHandler.postDelayed(this, pollInterval())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        running = true
        addButton()
        applyVisibility(AutoSwitchService.foregroundPackage)
        startPollingIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            if (held) {
                KeyImeService.instance?.releaseKey()
                held = false
            }
            buttonView?.text = Keys.buttonLabelFor(Prefs.getKeyCode(this))
            updateButtonState()
            applyVisibility(AutoSwitchService.foregroundPackage)
            stopPolling()
            startPollingIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        stopPolling()
        pollExecutor.shutdownNow()
        if (held) {
            KeyImeService.instance?.releaseKey()
            held = false
        }
        buttonView?.let { windowManager.removeView(it) }
        buttonView = null
        super.onDestroy()
    }

    /**
     * 只要配置了 Root / Shizuku，就持续轮询 dumpsys 作为前台来源。
     * 无障碍事件照常接收，两路来源都会经过 AutoSwitchService 的去抖器，
     * 重复的上报会被自动忽略，起到互相兜底的作用。
     */
    private fun shouldPoll(): Boolean =
        Prefs.getPrivilegeMode(this) != PrivilegeMode.ADB

    private fun pollInterval(): Long = 1500L

    private fun startPollingIfNeeded() {
        if (polling || !shouldPoll()) return
        polling = true
        mainHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun pollOnce() {
        val mode = Prefs.getPrivilegeMode(this)
        pollExecutor.execute {
            val windowDump = PrivilegedShell.exec(
                mode,
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
            )
            val imeDump = PrivilegedShell.exec(
                mode,
                "dumpsys input_method | grep -m1 'mInputShown='"
            )
            val pkg = parseForegroundPackage(windowDump)
            val focusPkg = parseCurrentFocusPackage(windowDump)
            // 键盘弹出时 mCurrentFocus 会变成输入法窗口，这比 mInputShown 更可靠
            val keyboardShown = if (focusPkg != null) {
                ImeSwitcher.isImePackage(this, focusPkg)
            } else {
                imeDump?.contains("mInputShown=true") == true
            }
            mainHandler.post { onPolled(pkg, keyboardShown) }
        }
    }

    /**
     * 优先取 mFocusedApp：输入法弹出时 mCurrentFocus 会变成输入法窗口，
     * 只有 mFocusedApp 才是真正的前台应用。
     */
    private fun parseForegroundPackage(dump: String?): String? {
        if (dump.isNullOrBlank()) return null
        val lines = dump.lines()
        val ordered = lines.filter { it.contains("mFocusedApp=") } +
            lines.filter { it.contains("mCurrentFocus=") } +
            lines
        return ordered.firstNotNullOfOrNull { parsePackage(it) }
    }

    private fun parseCurrentFocusPackage(dump: String?): String? {
        if (dump.isNullOrBlank()) return null
        val line = dump.lines().firstOrNull { it.contains("mCurrentFocus=") } ?: return null
        return parsePackage(line)
    }

    private fun parsePackage(line: String): String? {
        val match = Regex("""(?:\bu0\s+)?([A-Za-z0-9._]+)/[A-Za-z0-9._$]+""").find(line)
            ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.contains('.') }
    }

    private fun onPolled(pkg: String?, keyboardShown: Boolean) {
        // 前台变化统一交给去抖器处理，这里不再直接切换（悬浮窗显隐也要用）
        AutoSwitchService.notifyWindow(this, pkg, "轮询")
        if (!Prefs.isAutoSwitchEnabled(this)) return
        // 固定应用内：输入时切回原输入法，收起后切回本输入法
        AutoSwitchService.onKeyboardStateChanged(this, keyboardShown)
        // 固定应用内默认输入法不是本输入法时，直接切回来（输入中除外）
        AutoSwitchService.reconcile(this)

        // 未配置固定应用时，键盘弹出即切到本输入法
        if (!keyboardShown || !Prefs.isAutoSwitchOnFocus(this)) return
        if (Prefs.getFixedApps(this).isNotEmpty()) return
        val foreground = pkg ?: AutoSwitchService.foregroundPackage ?: return
        if (foreground == packageName) return
        if (ImeSwitcher.isImePackage(this, foreground)) return
        if (ImeSwitcher.isOurImeActive(this)) return
        if (ImeSwitcher.trySwitchToOurs(this) == SwitchResult.OK) {
            Toast.makeText(this, "已切换到悬浮按键输入法", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addButton() {
        if (buttonView != null) return
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (12 * density).toInt()
        }

        val view = TextView(this).apply {
            text = Keys.buttonLabelFor(Prefs.getKeyCode(this@OverlayService))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.floating_button_bg)
            elevation = 8 * density
        }
        view.setOnTouchListener(makeDragListener(params))
        view.setOnClickListener { sendKey() }

        windowManager.addView(view, params)
        buttonView = view
        updateButtonState()
    }

    private fun removeButton() {
        val view = buttonView ?: return
        if (held) {
            KeyImeService.instance?.releaseKey()
            held = false
        }
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // ignore
        }
        buttonView = null
    }

    /**
     * 配置了固定应用时，悬浮按钮只在固定应用前台显示；
     * 未配置固定应用或暂时拿不到前台信息时保持显示。
     */
    private fun applyVisibility(pkg: String?) {
        val fixed = Prefs.getFixedApps(this)
        val shouldShow = fixed.isEmpty() || pkg == null || fixed.contains(pkg)
        if (shouldShow) addButton() else removeButton()
    }

    private fun makeDragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var dragging = false

        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    view.alpha = 0.7f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = startX - event.rawX
                    val dy = event.rawY - startY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    if (dragging) true else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    true
                }
                else -> true
            }
        }
    }

    private fun sendKey() {
        val keyCode = Prefs.getKeyCode(this)
        val ime = KeyImeService.instance
        if (ime == null) {
            Toast.makeText(this, "请先启用并选择「悬浮按键输入法」", Toast.LENGTH_SHORT).show()
            return
        }

        if (Prefs.isHoldMode(this)) {
            if (!held) {
                if (ime.pressKeyDown(keyCode)) {
                    held = true
                    updateButtonState()
                } else {
                    Toast.makeText(this, "没有输入焦点，请先点按输入框", Toast.LENGTH_SHORT).show()
                }
            } else {
                ime.releaseKey()
                held = false
                updateButtonState()
            }
        } else {
            if (!ime.sendKey(keyCode)) {
                Toast.makeText(this, "没有输入焦点，请先点按输入框", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonState() {
        val view = buttonView ?: return
        val normal = Color.parseColor("#6750A4")
        val active = Color.parseColor("#B3261E")
        view.backgroundTintList = ColorStateList.valueOf(if (held) active else normal)
        val label = Keys.buttonLabelFor(Prefs.getKeyCode(this))
        view.text = if (held) "$label •" else label
    }
}
