package com.hhh.floatingkey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class InstallResult {
    /** 已通过 Root / Shizuku 静默安装。 */
    SILENT,

    /** 已拉起系统安装界面，等待用户确认。 */
    OPENED,

    /** 缺少「安装未知应用」权限，已跳转设置页。 */
    NEED_PERMISSION,

    /** 安装失败。 */
    FAILED
}

object UpdateManager {

    private const val REPO = "MignnLapin/HHH"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** 最近一次失败原因，供界面展示。 */
    @Volatile
    var lastError: String? = null
        private set

    data class Info(val version: String, val url: String, val notes: String)

    suspend fun checkForUpdate(current: String): Info? = withContext(Dispatchers.IO) {
        lastError = null
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val connection = URL(API_LATEST).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "FloatingKey")
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                connection.disconnect()
                if (code !in 200..299) {
                    lastError = if (code == 403) "GitHub 访问受限（HTTP 403）" else "HTTP $code"
                    if (code == 403 || code == 404) return@withContext null
                    continue
                }
                val json = JSONObject(body)
                val version = json.optString("tag_name").removePrefix("v").trim()
                if (version.isEmpty()) {
                    lastError = "发布信息缺少版本号"
                    return@withContext null
                }
                if (compareVersion(version, current) <= 0) return@withContext null
                val assets = json.optJSONArray("assets")
                var url = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (url.isEmpty()) {
                    lastError = "该版本没有可下载的 APK"
                    return@withContext null
                }
                return@withContext Info(version, url, json.optString("body"))
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        null
    }

    fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        lastError = null
        try {
            val target = File(context.cacheDir, "update.apk")
            if (target.exists()) target.delete()
            var current = URL(url)
            var connection: HttpURLConnection
            var redirects = 0
            while (true) {
                connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "FloatingKey")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null || ++redirects > 5) {
                        lastError = "下载重定向失败"
                        return@withContext null
                    }
                    current = URL(location)
                    continue
                }
                if (code != 200) {
                    connection.disconnect()
                    lastError = "下载失败（HTTP $code）"
                    return@withContext null
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                break
            }
            if (target.length() <= 0L) {
                lastError = "下载内容为空"
                return@withContext null
            }
            target
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** 安装已下载的 APK：优先 Root / Shizuku 静默安装，否则拉起系统安装器。 */
    fun install(context: Context, apk: File): InstallResult {
        lastError = null
        val mode = Prefs.getPrivilegeMode(context)
        if (mode == PrivilegeMode.ROOT || mode == PrivilegeMode.SHIZUKU) {
            val privileged = if (mode == PrivilegeMode.ROOT) {
                PrivilegedShell.rootAvailable || PrivilegedShell.refreshRoot()
            } else {
                PrivilegedShell.shizukuAvailable || PrivilegedShell.refreshShizuku()
            }
            if (privileged) {
                val silent = if (mode == PrivilegeMode.ROOT) {
                    installViaRoot(apk)
                } else {
                    installViaShizuku(apk)
                }
                if (silent) return InstallResult.SILENT
            }
        }
        launchInstaller(context, apk)
        return if (context.packageManager.canRequestPackageInstalls()) {
            InstallResult.OPENED
        } else {
            InstallResult.NEED_PERMISSION
        }
    }

    /** 打开系统安装界面；没有「安装未知应用」权限时跳转授权页。 */
    fun launchInstaller(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                lastError = "无法打开「安装未知应用」设置"
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            lastError = t.message ?: "无法启动系统安装器"
        }
    }

    private fun installViaRoot(apk: File): Boolean {
        val out = PrivilegedShell.exec(
            PrivilegeMode.ROOT,
            "pm install -r -d '${apk.absolutePath}'"
        )
        if (out?.contains("Success", ignoreCase = true) == true) return true
        lastError = out?.trim()?.takeIf { it.isNotEmpty() } ?: "Root 安装失败"
        return false
    }

    private fun installViaShizuku(apk: File): Boolean {
        return try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: return false
            if (!apk.exists() || apk.length() <= 0L) return false
            // Shizuku 以 shell 身份运行，读不到应用私有目录，直接把 APK 通过 stdin 交给 pm install
            val remote = service.newProcess(
                arrayOf("sh", "-c", "pm install -r -d -S ${apk.length()}"),
                null,
                null
            )
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
            }
            val exit = remote.waitFor()
            val text = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream())
                .bufferedReader().use { it.readText() } +
                ParcelFileDescriptor.AutoCloseInputStream(remote.getErrorStream())
                    .bufferedReader().use { it.readText() }
            remote.destroy()
            if (exit == 0 && text.contains("Success", ignoreCase = true)) {
                true
            } else {
                lastError = text.trim().takeIf { it.isNotEmpty() } ?: "Shizuku 安装失败"
                false
            }
        } catch (t: Throwable) {
            lastError = t.message ?: "Shizuku 安装失败"
            false
        }
    }
}
