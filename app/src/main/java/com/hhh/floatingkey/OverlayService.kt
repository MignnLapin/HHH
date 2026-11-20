package com.hhh.floatingkey

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_key_overlay"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH = "com.hhh.floatingkey.REFRESH"

        @Volatile
        var running = false
            private set

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        addButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            buttonView?.text = Keys.buttonLabelFor(Prefs.getKeyCode(this))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        buttonView?.let { windowManager.removeView(it) }
        buttonView = null
        super.onDestroy()
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
        when {
            ime == null -> Toast.makeText(
                this,
                "请先启用并选择「悬浮按键输入法」",
                Toast.LENGTH_SHORT
            ).show()
            !ime.sendKey(keyCode) -> Toast.makeText(
                this,
                "没有输入焦点，请先点按输入框",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
