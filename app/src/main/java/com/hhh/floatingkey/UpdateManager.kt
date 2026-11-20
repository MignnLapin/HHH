package com.hhh.floatingkey

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private const val USER_AGENT = "FloatingKey"

    /** GitHub 下载加速镜像，直连失败时依次尝试。 */
    private val MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh-proxy.com/"
    )

    /** 最近一次失败原因，供界面展示。 */
    @Volatile
    var lastError: String? = null
        private set

    data class Info(val version: String, val url: String, val notes: String)

    suspend fun checkForUpdate(current: String): Info? = withContext(Dispatchers.IO) {
        lastError = null
        val viaApi = checkViaApi(current)
        if (viaApi != null) return@withContext viaApi
        // API 正常但没有新版本
        if (lastError == null) return@withContext null
        // API 失败（常见为共享 IP 触发 GitHub 限流 403），改用 github.com 网页方式
        val apiError = lastError
        val viaWeb = checkViaWeb(current)
        if (viaWeb != null) {
            lastError = null
            return@withContext viaWeb
        }
        lastError = "$apiError；备用检查失败：${lastError ?: "网络异常"}"
        null
    }

    /** 官方 REST API：可能被共享出口 IP 的匿名限流拦成 403。 */
    private fun checkViaApi(current: String): Info? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val connection = URL(API_LATEST).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
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
                    lastError = if (code == 403) "GitHub API 访问受限（HTTP 403）" else "HTTP $code"
                    return null
                }
                val json = JSONObject(body)
                val version = json.optString("tag_name").removePrefix("v").trim()
                if (version.isEmpty()) {
                    lastError = "发布信息缺少版本号"
                    return null
                }
                if (compareVersion(version, current) <= 0) return null
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
                    return null
                }
                return Info(version, url, json.optString("body"))
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        return null
    }

    /** 不走 API：通过 releases/latest 重定向或 atom 订阅拿到最新版本号。 */
    private fun checkViaWeb(current: String): Info? {
        var tag = latestTagFromRedirect()
        if (tag == null) tag = latestTagFromAtom()
        if (tag == null) return null
        val version = tag.removePrefix("v").trim()
        if (version.isEmpty()) return null
        if (compareVersion(version, current) <= 0) return null
        val url = findApkUrl(tag, version)
        return Info(version, url, "")
    }

    private fun latestTagFromRedirect(): String? = try {
        val connection = URL("https://github.com/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (code in 300..399 && location != null) {
            location.substringAfter("/releases/tag/", "")
                .trim('/')
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    private fun latestTagFromAtom(): String? {
        return try {
            val body = httpGet("https://github.com/$REPO/releases.atom") ?: return null
            Regex("/releases/tag/([^\"<>/]+)").find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun findApkUrl(tag: String, version: String): String {
        // expanded_assets 是公开页面，不受 API 限流影响
        val html = httpGet("https://github.com/$REPO/releases/expanded_assets/$tag")
        if (html != null) {
            Regex("href=\"([^\"]+\\.apk)\"", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { href ->
                    return if (href.startsWith("http")) href else "https://github.com$href"
                }
        }
        // 约定命名兜底：FloatingKey-<版本>.apk
        return "https://github.com/$REPO/releases/download/$tag/FloatingKey-$version.apk"
    }

    private fun httpGet(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            null
        } else {
            connection.inputStream.bufferedReader().use { it.readText() }
                .also { connection.disconnect() }
        }
    } catch (t: Throwable) {
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
        // GitHub Release 资产会跳转到 objects.githubusercontent.com，国内常被墙。
        // 直连失败时依次尝试公开加速镜像。
        val candidates = buildList {
            add(url)
            if (url.startsWith("https://github.com/")) {
                MIRRORS.forEach { add(it + url) }
            }
        }
        var message: String? = null
        for (candidate in candidates) {
            val target = try {
                downloadOnce(context, candidate)
            } catch (t: Throwable) {
                message = t.message ?: t.javaClass.simpleName
                null
            }
            if (target != null && verifyApk(context, target)) return@withContext target
            if (target != null) {
                message = "下载的安装包校验失败"
                target.delete()
            }
        }
        lastError = message ?: "网络异常"
        null
    }

    private fun downloadOnce(context: Context, url: String): File? {
        val target = File(context.cacheDir, "update.apk")
        if (target.exists()) target.delete()
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null || ++redirects > 5) {
                    lastError = "下载重定向失败"
                    return null
                }
                current = URL(location)
                continue
            }
            if (code != 200) {
                connection.disconnect()
                lastError = "下载失败（HTTP $code）"
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            break
        }
        if (target.length() <= 0L) {
            lastError = "下载内容为空"
            return null
        }
        return target
    }

    /** 校验下载到的 APK 确实是本应用且版本更高，避免镜像返回错误页面。 */
    private fun verifyApk(context: Context, file: File): Boolean {
        return try {
            if (file.length() <= 0L) return false
            file.inputStream().use { input ->
                val magic = ByteArray(2)
                if (input.read(magic) != 2 || magic[0] != 'P'.code.toByte() ||
                    magic[1] != 'K'.code.toByte()
                ) {
                    return false
                }
            }
            val archive = context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return false
            if (archive.packageName != context.packageName) return false
            val newCode = if (Build.VERSION.SDK_INT >= 28) {
                archive.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archive.versionCode.toLong()
            }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            val installedCode = if (Build.VERSION.SDK_INT >= 28) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }
            newCode > installedCode
        } catch (t: Throwable) {
            false
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
