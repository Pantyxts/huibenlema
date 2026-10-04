package com.huibenlema.app.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.huibenlema.app.BuildConfig
import com.huibenlema.app.data.remote.lng
import com.huibenlema.app.data.remote.str
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** 更新信息（GitHub Release） */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String = ""
)

/**
 * 自动更新：查询 GitHub Releases → 下载 APK → 拉起系统安装器。
 * 下载走独立 OkHttpClient（长超时、无节流拦截）。
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class Available(val info: UpdateInfo) : UpdateState()
        data object NoUpdate : UpdateState()
        /** 正在连接下载服务器（含切换镜像重试） */
        data object Connecting : UpdateState()
        data class Downloading(
            /** 百分比 0-100；未知大小（无 Content-Length）时为 -1 */
            val percent: Int,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val speedBytesPerSec: Long
        ) : UpdateState()
        data class Downloaded(val file: File) : UpdateState()
        data class Failed(val message: String) : UpdateState()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 手动检查：更新 UI 状态 */
    suspend fun checkUpdate(): UpdateInfo? {
        _state.value = UpdateState.Checking
        return try {
            val info = checkUpdateSilent()
            when {
                info == null && _state.value == UpdateState.Checking ->
                    _state.value = UpdateState.NoUpdate
                info != null -> _state.value = UpdateState.Available(info)
            }
            info
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("检查失败：${e.message ?: "网络错误"}")
            null
        }
    }

    /** 静默检查（启动自动检查用，不改 UI 状态） */
    suspend fun checkUpdateSilent(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/Pantyxts/huibenlema/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val json = resp.body?.string()?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject
            } ?: return@withContext null
            val tag = json.str("tag_name")
            val assets = json["assets"] as? JsonArray
            val apk = assets?.mapNotNull { it as? JsonObject }?.firstOrNull {
                it.str("name").endsWith(".apk")
            }
            val url = apk?.str("browser_download_url") ?: ""
            if (tag.isBlank() || url.isBlank()) return@withContext null
            val info = UpdateInfo(
                versionName = tag.removePrefix("v"),
                downloadUrl = url,
                releaseNotes = json.str("body")
            )
            if (isNewer(info.versionName, BuildConfig.VERSION_NAME)) info else null
        }
    }

    /**
     * 下载 APK 到应用私有目录（进度写入 state）。
     * 官方地址优先；国内网络不通时自动切换加速镜像重试。
     */
    suspend fun download(info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val urls = buildList {
            add(info.downloadUrl)
            // 国内加速镜像（ghproxy 类；签名校验兜底安全，篡改包无法通过系统签名验证）
            add("https://ghfast.top/" + info.downloadUrl)
            add("https://gh-proxy.com/" + info.downloadUrl)
        }
        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        val file = File(dir, "huibenlema-v${info.versionName}.apk")
        var lastError: Exception? = null
        for (url in urls) {
            try {
                _state.value = UpdateState.Connecting
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("空响应")
                    val total = body.contentLength()
                    file.outputStream().use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastSampleAt = 0L
                        var lastSampleRead = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            // 每 500ms 采样一次速度并刷新进度
                            val now = System.currentTimeMillis()
                            if (now - lastSampleAt >= 500) {
                                val speed = if (lastSampleAt > 0) (read - lastSampleRead) * 1000 / (now - lastSampleAt) else 0L
                                lastSampleAt = now
                                lastSampleRead = read
                                _state.value = UpdateState.Downloading(
                                    percent = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1,
                                    downloadedBytes = read,
                                    totalBytes = total,
                                    speedBytesPerSec = speed
                                )
                            }
                        }
                    }
                }
                _state.value = UpdateState.Downloaded(file)
                return@withContext file
            } catch (e: Exception) {
                lastError = e
                // 当前地址失败，尝试下一个镜像
            }
        }
        _state.value = UpdateState.Failed("下载失败：${lastError?.message ?: "网络错误"}")
        null
    }

    /** 拉起系统安装器（需要用户已允许"安装未知应用"） */
    fun install(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android-package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("无法打开安装器：${e.message}")
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    companion object {
        /** 按点分数字比较版本：remote > local 才算更新 */
        private fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split(".").mapNotNull { it.toIntOrNull() }
            val l = local.split(".").mapNotNull { it.toIntOrNull() }
            if (r.isEmpty() || l.isEmpty()) return false
            for (i in 0 until maxOf(r.size, l.size)) {
                val rv = r.getOrElse(i) { 0 }
                val lv = l.getOrElse(i) { 0 }
                if (rv != lv) return rv > lv
            }
            return false
        }
    }
}
