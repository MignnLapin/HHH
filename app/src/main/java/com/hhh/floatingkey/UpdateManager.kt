package com.hhh.floatingkey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val REPO = "MignnLapin/HHH"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Info(val version: String, val url: String, val notes: String)

    suspend fun checkForUpdate(current: String): Info? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_LATEST).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FloatingKey")
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val version = json.optString("tag_name").removePrefix("v").trim()
            if (version.isEmpty()) return@withContext null
            if (compareVersion(version, current) <= 0) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var url = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = asset.optString("browser_download_url")
                    break
                }
            }
            if (url.isEmpty()) return@withContext null
            Info(version, url, json.optString("body"))
        } catch (t: Throwable) {
            null
        }
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
        try {
            val target = File(context.cacheDir, "update.apk")
            if (target.exists()) target.delete()
            var current = URL(url)
            var connection: HttpURLConnection
            var redirects = 0
            while (true) {
                connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "FloatingKey")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    current = URL(location)
                    connection.disconnect()
                    if (++redirects > 5) return@withContext null
                    continue
                }
                if (code != 200) {
                    connection.disconnect()
                    return@withContext null
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                break
            }
            if (target.length() > 0) target else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 返回是否静默安装成功。 */
    fun install(context: Context, apk: File): Boolean {
        val mode = Prefs.getPrivilegeMode(context)
        if (mode == PrivilegeMode.ROOT &&
            (PrivilegedShell.rootAvailable || PrivilegedShell.refreshRoot())
        ) {
            PrivilegedShell.exec(
                PrivilegeMode.ROOT,
                "pm install -r -d '${apk.absolutePath}'"
            )
            return true
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                // ignore
            }
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return false
    }
}
