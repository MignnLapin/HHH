package com.hhh.floatingkey

import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_key_overlay"
        private const val NOTIFICATION_ID = 1
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
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun refresh(context: Context) {
            if (running) {
                context.startForegroundService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var buttonView: TextView? = null
    private var held = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadExecutor()
    private var polling = false
    private var lastPollPkg: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!shouldPoll()) {
                polling = false
                return
            }
            pollOnce()
            mainHandler.postDelayed(this, 1200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
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
     * Root / Shizuku 模式下若未开启无障碍，则通过轮询 dumpsys 检测前台应用与键盘状态。
     */
    private fun shouldPoll(): Boolean =
        Prefs.isAutoSwitchEnabled(this) &&
            Prefs.getPrivilegeMode(this) != PrivilegeMode.ADB &&
            !AutoSwitchService.isEnabled(this)

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
            val pkg = windowDump?.let {
                Regex("(?:u0\\s+)?([A-Za-z0-9._]+)/").find(it)?.groupValues?.get(1)
            }
            val keyboardShown = imeDump?.contains("mInputShown=true") == true
            mainHandler.post { onPolled(pkg, keyboardShown) }
        }
    }

    private fun onPolled(pkg: String?, keyboardShown: Boolean) {
        if (!Prefs.isAutoSwitchEnabled(this)) return
        val fixed = Prefs.getFixedApps(this)

        if (pkg != null && pkg != packageName && pkg != lastPollPkg) {
            lastPollPkg = pkg
            AutoSwitchService.setForeground(pkg)
            when {
                fixed.contains(pkg) -> ImeSwitcher.switchToOurs(this)
                fixed.isNotEmpty() -> ImeSwitcher.restorePrevious(this)
            }
        }

        val foreground = pkg ?: AutoSwitchService.foregroundPackage
        val inFixedApp = foreground != null && fixed.contains(foreground)
        // 配置了固定应用后，焦点切换只在固定应用内生效
        val focusAllowed = fixed.isEmpty() || inFixedApp

        if (keyboardShown && Prefs.isAutoSwitchOnFocus(this) && focusAllowed &&
            !ImeSwitcher.isOurImeActive(this)
        ) {
            ImeSwitcher.switchToOurs(this)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "悬浮按钮", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("悬浮按键")
            .setContentText("悬浮按钮显示中")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
