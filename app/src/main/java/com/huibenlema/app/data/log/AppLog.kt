package com.huibenlema.app.data.log

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 轻量应用日志：release 只落盘（filesDir/logs/app.log，256KB 轮转保留一份），
 * debug 额外镜像到 Logcat。用户设备不用 adb，靠「设置 → 导出日志」回传排查。
 *
 * 规则：全项目禁止把凭证（cookie/apiKey）原文写进日志，一律经 [mask] 脱敏。
 */
object AppLog {

    private const val TAG = "HBLog"
    private const val MAX_FILE_BYTES = 256L * 1024L
    private const val FILE_NAME = "app.log"
    private const val OLD_FILE_NAME = "app.log.1"
    private const val CRASH_FILE_NAME = "last_crash.txt"

    @Volatile
    private var initialized = false
    private var isDebug = false
    private var logDir: File? = null
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    /** 应用入口第一行调用；同时注册全局崩溃处理器（先落盘再转交，不吞崩溃）。 */
    fun init(app: Context, debug: Boolean) {
        if (initialized) return
        initialized = true
        isDebug = debug
        logDir = File(app.filesDir, "logs").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                e(TAG, "未捕获异常 thread=${t.name}", e)
                flushBlocking()
                // 独立崩溃文件：重启后 App 弹出崩溃信息对话框，用户可直接查看栈
                File(logDir, CRASH_FILE_NAME).writeText(
                    buildString {
                        append("time=").append(System.currentTimeMillis()).append('\n')
                        append("thread=").append(t.name).append('\n')
                        append(e.stackTraceToString()).append('\n')
                    }
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /** 凭证脱敏：只保留前 4 位与长度，绝不落明文。 */
    fun mask(s: String): String = if (s.length <= 8) "***" else s.take(4) + "…(len=${s.length})"

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (!initialized) return
        val line = buildString {
            append(timeFormat.get().format(Date()))
            append(" [").append(tag).append("] ").append(msg)
            if (t != null) {
                append('\n')
                append(t.stackTraceToString().lines().take(12).joinToString("\n"))
            }
        }
        if (isDebug) {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
                else -> Log.e(tag, msg, t)
            }
        }
        val dir = logDir ?: return
        writerExecutor.execute {
            runCatching {
                val file = File(dir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) {
                    File(dir, OLD_FILE_NAME).delete()
                    file.renameTo(File(dir, OLD_FILE_NAME))
                }
                BufferedWriter(FileWriter(File(dir, FILE_NAME), true)).use { w ->
                    w.appendLine(line)
                }
            }
        }
    }

    /** 上次崩溃的栈信息（重启后弹窗显示用）；无崩溃返回 null */
    fun readLastCrash(): String? = logDir?.let {
        File(it, CRASH_FILE_NAME).takeIf { f -> f.exists() }?.readText()?.takeIf { t -> t.isNotBlank() }
    }

    /** 用户已查看崩溃信息，删除标记文件 */
    fun clearLastCrash() {
        logDir?.let { File(it, CRASH_FILE_NAME).delete() }
    }

    /** 崩溃处理器专用：同步落盘当前文件（含待写队列）。 */
    fun flushBlocking() {
        if (!initialized) return
        writerExecutor.execute {
            runCatching {
                BufferedWriter(FileWriter(File(logDir, FILE_NAME), true)).use { w ->
                    w.appendLine("-- flush --")
                }
            }
        }
    }

    /** 导出全部日志（旧文件 + 当前文件，旧→新）到指定 SAF uri；追加环境信息头。返回是否成功。 */
    fun exportTo(context: Context, uri: Uri): Boolean {
        val dir = logDir
        if (dir == null) return false
        return runCatching {
            val header = buildString {
                appendLine("=== huibenlema log ===")
                appendLine("version=").append(BuildInfo.versionName(context)).append('(')
                    .append(BuildInfo.versionCode(context)).append(')')
                appendLine("device=").append(android.os.Build.MODEL)
                    .append(" sdk=").append(android.os.Build.VERSION.SDK_INT)
                appendLine("time=").append(System.currentTimeMillis())
                appendLine()
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(header.toByteArray())
                File(dir, OLD_FILE_NAME).takeIf { it.exists() }?.let { out.write(it.readBytes()) }
                File(dir, FILE_NAME).takeIf { it.exists() }?.let { out.write(it.readBytes()) }
                true
            } ?: false
        }.getOrDefault(false)
    }

    /** 构建信息（避免日志层直接依赖 BuildConfig 包路径差异）。 */
    object BuildInfo {
        private const val UNKNOWN = "?"
        private var versionName: String = UNKNOWN
        private var versionCode: String = UNKNOWN

        fun init(context: Context) {
            runCatching {
                val pkg = context.applicationContext.packageName
                val info = context.packageManager.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                versionName = info.versionName ?: UNKNOWN
                @Suppress("DEPRECATION")
                versionCode = info.versionCode.toString()
            }
        }

        fun versionName(context: Context): String {
            if (versionName == UNKNOWN) init(context)
            return versionName
        }

        fun versionCode(context: Context): String {
            if (versionCode == UNKNOWN) init(context)
            return versionCode
        }
    }
}
