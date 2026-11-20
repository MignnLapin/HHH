package com.hhh.floatingkey

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * 输入法切换。三种权限方式：
 * - ADB：WRITE_SECURE_SETTINGS（adb shell pm grant 一次性授权）
 * - Root：su -c settings put
 * - Shizuku：通过 Shizuku shell 执行
 */
object ImeSwitcher {

    const val GRANT_COMMAND =
        "adb shell pm grant com.hhh.floatingkey android.permission.WRITE_SECURE_SETTINGS"

    fun ourImeId(context: Context): String =
        ComponentName(context, KeyImeService::class.java).flattenToShortString()

    fun currentImeId(context: Context): String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )

    fun isOurImeActive(context: Context): Boolean =
        currentImeId(context) == ourImeId(context)

    fun isOurImeEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.id == ourImeId(context) }
    }

    /** 当前权限方式是否可用（会做一次阻塞检测）。 */
    fun canSwitch(context: Context): Boolean = when (Prefs.getPrivilegeMode(context)) {
        PrivilegeMode.ROOT -> PrivilegedShell.rootAvailable || PrivilegedShell.refreshRoot()
        PrivilegeMode.SHIZUKU ->
            PrivilegedShell.shizukuAvailable || PrivilegedShell.refreshShizuku()
        PrivilegeMode.ADB ->
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun canSwitchCached(context: Context): Boolean = when (Prefs.getPrivilegeMode(context)) {
        PrivilegeMode.ROOT -> PrivilegedShell.rootAvailable
        PrivilegeMode.SHIZUKU -> PrivilegedShell.shizukuAvailable
        PrivilegeMode.ADB ->
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isEnabled(context: Context, imeId: String): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.id == imeId }
    }

    /** 切换到本应用的输入法，并记住切换前的输入法以便恢复。 */
    fun switchToOurs(context: Context): Boolean {
        if (!Prefs.isAutoSwitchEnabled(context)) return false
        if (!canSwitchCached(context) && !canSwitch(context)) return false
        if (!isOurImeEnabled(context)) return false
        val ours = ourImeId(context)
        val current = currentImeId(context)
        if (current == ours) return true
        if (current != null) Prefs.setRestoreIme(context, current)
        return writeDefaultIme(context, ours)
    }

    /** 恢复自动切换前的输入法。 */
    fun restorePrevious(context: Context): Boolean {
        if (!canSwitchCached(context) && !canSwitch(context)) return false
        val current = currentImeId(context)
        if (current != ourImeId(context)) {
            Prefs.setRestoreIme(context, null)
            return true
        }
        val previous = Prefs.getRestoreIme(context) ?: return false
        if (previous == ourImeId(context) || !isEnabled(context, previous)) {
            Prefs.setRestoreIme(context, null)
            return false
        }
        val ok = writeDefaultIme(context, previous)
        if (ok) Prefs.setRestoreIme(context, null)
        return ok
    }

    private fun writeDefaultIme(context: Context, imeId: String): Boolean {
        val mode = Prefs.getPrivilegeMode(context)
        if (mode != PrivilegeMode.ADB) {
            val out = PrivilegedShell.exec(
                mode,
                "settings put secure default_input_method $imeId"
            ) ?: return false
            // settings put 无输出；读回校验
            return currentImeId(context) == imeId ||
                PrivilegedShell.exec(
                    mode,
                    "settings get secure default_input_method"
                )?.trim() == imeId
        }
        return try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                imeId
            )
        } catch (e: Exception) {
            false
        }
    }
}
