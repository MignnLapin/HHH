package com.hhh.floatingkey

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
