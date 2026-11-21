package com.hhh.floatingkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 开机 / 应用更新后自启动：恢复无障碍服务与悬浮按钮。
 * 只在用户没有手动关闭「显示悬浮按钮」且已授予悬浮窗权限时启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        AppLog.log(context, "boot", "自启动（$action）")
        PrivilegedShell.ensureAccessibility(context)
        if (Prefs.isOverlayEnabled(context) && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
