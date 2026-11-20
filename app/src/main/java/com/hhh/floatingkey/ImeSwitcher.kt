package com.hhh.floatingkey

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** 自动切换输入法的结果，用于界面提示与日志。 */
enum class SwitchResult(val message: String) {
    OK("已切换到悬浮按键输入法"),
    NOTHING("当前已是悬浮按键输入法"),
    AUTO_OFF("自动切换已关闭"),
    IME_NOT_ENABLED("「悬浮按键输入法」未在系统设置中启用"),
    NO_PERMISSION("缺少授权，请先完成「权限方式」设置"),
    WRITE_FAILED("写入系统输入法设置失败")
}

/**
 * 输入法切换。三种权限方式：
 * - ADB：WRITE_SECURE_SETTINGS（adb shell pm grant 一次性授权）
 * - Root：su 执行；可自动 pm grant 本应用，之后直接写 Settings
 * - Shizuku：通过 Shizuku shell 执行；同样可自动授权
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

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isOurImeEnabled(context: Context): Boolean = enabledImeIds(context).contains(ourImeId(context))

    @Volatile
    private var imePackages: Set<String> = emptySet()

    @Volatile
    private var imePackagesAt = 0L

    /**
     * 是否是输入法自身的窗口包名。
     * 切换默认输入法时，旧输入法的键盘窗口会关闭并抛出 TYPE_WINDOW_STATE_CHANGED，
     * 如果不排除，会被误判成「切到了别的应用」从而立刻切回原输入法。
     */
    fun isImePackage(context: Context, pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - imePackagesAt > 60_000) {
            imePackages = try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.enabledInputMethodList.map { it.packageName }.toSet()
            } catch (t: Throwable) {
                emptySet()
            }
            imePackagesAt = now
        }
        return imePackages.contains(pkg)
    }

    private fun enabledImeIds(context: Context): List<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val fromManager = try {
            imm.enabledInputMethodList.map { it.id }
        } catch (t: Throwable) {
            emptyList()
        }
        if (fromManager.isNotEmpty()) return fromManager
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        )?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** 当前权限方式是否可用（会做一次阻塞探测）。 */
    fun canSwitch(context: Context): Boolean {
        if (hasSecureSettings(context)) return true
        return when (Prefs.getPrivilegeMode(context)) {
            PrivilegeMode.ROOT -> PrivilegedShell.rootAvailable || PrivilegedShell.refreshRoot()
            PrivilegeMode.SHIZUKU ->
                PrivilegedShell.shizukuAvailable || PrivilegedShell.refreshShizuku()
            PrivilegeMode.ADB -> false
        }
    }

    fun canSwitchCached(context: Context): Boolean {
        if (hasSecureSettings(context)) return true
        return when (Prefs.getPrivilegeMode(context)) {
            PrivilegeMode.ROOT -> PrivilegedShell.rootAvailable
            PrivilegeMode.SHIZUKU -> PrivilegedShell.shizukuAvailable
            PrivilegeMode.ADB -> false
        }
    }

    /** 切换到本应用输入法，返回具体结果便于提示。 */
    fun trySwitchToOurs(context: Context): SwitchResult {
        if (!Prefs.isAutoSwitchEnabled(context)) return SwitchResult.AUTO_OFF
        if (!isOurImeEnabled(context)) {
            AppLog.log(context, "ime", "切换失败：本输入法未启用")
            return SwitchResult.IME_NOT_ENABLED
        }
        if (!canSwitch(context)) {
            AppLog.log(context, "ime", "切换失败：没有写入权限（${Prefs.getPrivilegeMode(context)}）")
            return SwitchResult.NO_PERMISSION
        }
        val ours = ourImeId(context)
        val current = currentImeId(context)
        if (current == ours) return SwitchResult.NOTHING
        if (current != null) Prefs.setRestoreIme(context, current)
        val result = if (writeDefaultIme(context, ours)) SwitchResult.OK else SwitchResult.WRITE_FAILED
        AppLog.log(context, "ime", "切换到本输入法，原输入法=$current, 结果=$result")
        return result
    }

    fun switchToOurs(context: Context): Boolean = trySwitchToOurs(context) == SwitchResult.OK

    /** 恢复自动切换前的输入法。 */
    fun restorePrevious(context: Context): Boolean {
        if (!canSwitchCached(context) && !canSwitch(context)) {
            AppLog.log(context, "ime", "恢复输入法失败：没有写入权限")
            return false
        }
        val ours = ourImeId(context)
        val current = currentImeId(context)
        if (current != ours) {
            Prefs.setRestoreIme(context, null)
            return true
        }
        val enabled = enabledImeIds(context)
        val previous = Prefs.getRestoreIme(context)
            ?.takeIf { it != ours && enabled.contains(it) }
            // 记录丢失时从已启用的输入法里挑一个（优先最后一个）
            ?: enabled.lastOrNull { it != ours }
            ?: run {
                AppLog.log(context, "ime", "恢复输入法失败：没有可用的其他输入法")
                return false
            }
        val ok = writeDefaultIme(context, previous)
        AppLog.log(context, "ime", "恢复输入法 -> $previous, 结果=$ok")
        if (ok) Prefs.setRestoreIme(context, null)
        return ok
    }

    private fun writeDefaultIme(context: Context, imeId: String): Boolean {
        // 有 WRITE_SECURE_SETTINGS 时直接写（最快，且不依赖 shell）
        if (hasSecureSettings(context)) {
            return try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    imeId
                ) && currentImeId(context) == imeId
            } catch (e: Exception) {
                false
            }
        }
        val mode = Prefs.getPrivilegeMode(context)
        if (mode == PrivilegeMode.ADB) return false
        val out = PrivilegedShell.exec(mode, "settings put secure default_input_method $imeId") ?: return false
        if (currentImeId(context) == imeId) return true
        return PrivilegedShell.exec(mode, "settings get secure default_input_method")
            ?.trim() == imeId && out.isNotEmpty()
    }
}
