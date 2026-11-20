package com.hhh.floatingkey

import android.content.Context
import android.view.KeyEvent

object Prefs {

    private const val FILE_NAME = "floating_key_config"

    private const val KEY_KEY_CODE = "key_code"
    private const val KEY_HOLD_MODE = "hold_mode"
    private const val KEY_AUTO_SWITCH = "auto_switch"
    private const val KEY_AUTO_SWITCH_FOCUS = "auto_switch_focus"
    private const val KEY_FIXED_APPS = "fixed_apps"
    private const val KEY_RESTORE_IME = "restore_ime"
    private const val KEY_PRIVILEGE_MODE = "privilege_mode"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getKeyCode(context: Context): Int =
        sp(context).getInt(KEY_KEY_CODE, KeyEvent.KEYCODE_A)

    fun setKeyCode(context: Context, keyCode: Int) {
        sp(context).edit().putInt(KEY_KEY_CODE, keyCode).apply()
    }

    /** true = 按住切换模式（按一次按下，再按一次松开），false = 单次按键。 */
    fun isHoldMode(context: Context): Boolean =
        sp(context).getBoolean(KEY_HOLD_MODE, false)

    fun setHoldMode(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HOLD_MODE, value).apply()
    }

    fun isAutoSwitchEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_SWITCH, true)

    fun setAutoSwitchEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_SWITCH, value).apply()
    }

    fun isAutoSwitchOnFocus(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_SWITCH_FOCUS, true)

    fun setAutoSwitchOnFocus(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_SWITCH_FOCUS, value).apply()
    }

    fun getFixedApps(context: Context): Set<String> =
        sp(context).getStringSet(KEY_FIXED_APPS, emptySet())?.toSet() ?: emptySet()

    fun setFixedApps(context: Context, packages: Set<String>) {
        sp(context).edit().putStringSet(KEY_FIXED_APPS, packages).apply()
    }

    /** 自动切换前用户使用的输入法，用于切回。 */
    fun getRestoreIme(context: Context): String? =
        sp(context).getString(KEY_RESTORE_IME, null)

    fun setRestoreIme(context: Context, imeId: String?) {
        sp(context).edit().putString(KEY_RESTORE_IME, imeId).apply()
    }

    fun getPrivilegeMode(context: Context): PrivilegeMode =
        PrivilegeMode.fromKey(sp(context).getString(KEY_PRIVILEGE_MODE, null))

    fun setPrivilegeMode(context: Context, mode: PrivilegeMode) {
        sp(context).edit().putString(KEY_PRIVILEGE_MODE, mode.key).apply()
    }

    fun isAutoUpdate(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdate(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_UPDATE, value).apply()
    }

    /** 「显示悬浮按钮」开关，默认打开。 */
    fun isOverlayEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_OVERLAY_ENABLED, true)

    fun setOverlayEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()
    }
}
