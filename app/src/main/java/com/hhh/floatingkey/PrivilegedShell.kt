package com.hhh.floatingkey

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

enum class PrivilegeMode(val key: String, val label: String) {
    ADB("adb", "ADB"),
    ROOT("root", "Root"),
    SHIZUKU("shizuku", "Shizuku");

    companion object {
        fun fromKey(key: String?): PrivilegeMode =
            values().firstOrNull { it.key == key } ?: ADB
    }
}

/**
 * 特权命令执行：Root 走 su，Shizuku 走其 shell 服务，ADB 模式直接写 Settings.Secure。
 */
object PrivilegedShell {

    @Volatile
    var rootAvailable: Boolean = false
        private set

    @Volatile
    var shizukuAvailable: Boolean = false
        private set

    fun isShizukuInstalled(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun refreshRoot(): Boolean {
        val out = execRoot("id")
        rootAvailable = out?.contains("uid=0") == true
        return rootAvailable
    }

    fun refreshShizuku(): Boolean {
        shizukuAvailable = try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        return shizukuAvailable
    }

    fun exec(mode: PrivilegeMode, command: String): String? = when (mode) {
        PrivilegeMode.ROOT -> execRoot(command)
        PrivilegeMode.SHIZUKU -> execShizuku(command)
        PrivilegeMode.ADB -> null
    }

    /** 用 Root / Shizuku 把 WRITE_SECURE_SETTINGS 授予本应用，之后可直接写系统设置。 */
    fun grantSecureSettings(context: Context, mode: PrivilegeMode): Boolean {
        if (mode == PrivilegeMode.ADB) return false
        exec(mode, "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 用 Root / Shizuku 直接开启无障碍服务（免手动去设置里点）。 */
    fun enableAccessibility(context: Context, mode: PrivilegeMode): Boolean {
        if (mode == PrivilegeMode.ADB) return false
        val component = ComponentName(context, AutoSwitchService::class.java).flattenToString()
        val cmd = "c=\$(settings get secure enabled_accessibility_services); " +
            "case \":\$c:\" in *\":$component:\"*) : ;; " +
            "*) if [ \"\$c\" = \"null\" ] || [ -z \"\$c\" ]; then " +
            "settings put secure enabled_accessibility_services '$component'; else " +
            "settings put secure enabled_accessibility_services \"\$c:$component\"; fi ;; esac; " +
            "settings put secure accessibility_enabled 1"
        exec(mode, cmd)
        return AutoSwitchService.isEnabled(context)
    }

    /** 关闭无障碍服务（Root / Shizuku）。 */
    fun disableAccessibility(context: Context, mode: PrivilegeMode): Boolean {
        if (mode == PrivilegeMode.ADB) return false
        val component = ComponentName(context, AutoSwitchService::class.java).flattenToString()
        val cmd = "c=\$(settings get secure enabled_accessibility_services); " +
            "n=\$(echo \":\$c:\" | sed 's#:$component:##; s#^:##; s#:\$##'); " +
            "settings put secure enabled_accessibility_services \"\$n\""
        exec(mode, cmd)
        return !AutoSwitchService.isEnabled(context)
    }

    private fun execRoot(command: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        process.waitFor(6, TimeUnit.SECONDS)
        out
    } catch (t: Throwable) {
        null
    }

    private fun execShizuku(command: String): String? = try {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: return null
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
        val out = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream())
            .bufferedReader().use { it.readText() }
        ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream()).use { it.readBytes() }
        remote.waitFor()
        remote.destroy()
        out
    } catch (t: Throwable) {
        null
    }
}
