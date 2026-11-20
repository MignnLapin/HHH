package com.hhh.floatingkey

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简单的运行日志：写入应用私有目录，可在主界面「查看日志」里阅读或复制。
 * 主要记录更新检查、下载、安装与输入法切换，方便定位问题。
 */
object AppLog {

    private const val FILE_NAME = "app_log.txt"
    private const val MAX_BYTES = 256L * 1024L
    private const val KEEP_LINES = 600

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun log(context: Context, tag: String, message: String) {
        synchronized(this) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    val kept = file.readLines().takeLast(KEEP_LINES)
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
                val line = "${timeFormat.format(Date())} [$tag] $message\n"
                file.appendText(line)
            } catch (t: Throwable) {
                // 日志失败不影响主流程
            }
        }
    }

    fun read(context: Context): String = synchronized(this) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists() || file.length() == 0L) "暂无日志" else file.readText()
        } catch (t: Throwable) {
            "读取日志失败：${t.message}"
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            try {
                File(context.filesDir, FILE_NAME).delete()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}
