package com.aoooa.adb.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.aoooa.adb.AdbManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 远程更新信息实体
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val body: String,
    val downloadUrl: String,
    val fileSize: Long
)

/**
 * 应用更新检测与下载安装管理
 */
object UpdateChecker {

    private const val RELEASES_API = "https://api.github.com/repos/aoooa101/aoooa-adb-android/releases/latest"

    /** 自建更新检查接口（Serv00 波兰节点，数据源 releases.atom，无限流、无 API 配额依赖） */
    private const val SELF_HOSTED_API = "https://aoooa.dpdns.org/app/check"

    private val executor = Executors.newSingleThreadExecutor()

    /** 记录最新检测到的远端版本号（如 "2.7.2"） */
    var latestRemoteVersion: String? = null

    /**
     * 检查远程是否有新版本
     * @param currentVersion 当前本地版本号（如 "2.6.0"）
     * @param onResult 回调：(updateInfo, isLatest, errorMsg)
     */
    fun checkUpdate(
        currentVersion: String,
        onResult: (UpdateInfo?, Boolean, String?) -> Unit
    ) {
        executor.execute {
            // 通道一：自建更新接口（优先；Serv00 节点连通 GitHub 稳定，缓存 6 小时）
            if (trySelfHosted(currentVersion, onResult)) return@execute
            // 通道二：GitHub API 直连（回退保底，原逻辑不动）
            try {
                val url = URL(RELEASES_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "aoooa-adb-updater")

                val code = conn.responseCode
                if (code == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()

                    val json = JSONObject(sb.toString())
                    val tagName = json.optString("tag_name", "").trim()
                    val body = json.optString("body", "").trim()
                    val rawVer = tagName.removePrefix("v").removePrefix("V").trim()
                    latestRemoteVersion = rawVer

                    // 解析 assets，优先匹配 arm64-v8a 或 release 包
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    var fileSize = 0L

                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            val urlStr = asset.optString("browser_download_url", "")
                            val size = asset.optLong("size", 0L)
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                if (name.contains("arm64", ignoreCase = true) || downloadUrl.isEmpty()) {
                                    downloadUrl = urlStr
                                    fileSize = size
                                }
                            }
                        }
                    }

                    val isNewer = isNewerVersion(rawVer, currentVersion)
                    if (isNewer && downloadUrl.isNotEmpty()) {
                        val info = UpdateInfo(
                            tagName = tagName.ifBlank { "v$rawVer" },
                            versionName = rawVer,
                            body = if (body.isNotBlank()) body else "修复已知问题并优化体验",
                            downloadUrl = downloadUrl,
                            fileSize = fileSize
                        )
                        onResult(info, false, null)
                    } else {
                        onResult(null, true, null)
                    }
                } else if (code == 403 || code == 429) {
                    onResult(null, false, "GitHub API 请求频率受限 (HTTP $code)")
                } else {
                    onResult(null, false, "请求失败 (HTTP $code)")
                }
            } catch (e: Exception) {
                onResult(null, false, e.message ?: "网络连接异常")
            }
        }
    }

    /**
     * 通道一：自建更新接口（Serv00 节点，数据源 releases.atom，无 API 配额依赖）。
     * 成功处理（无论有无更新）返回 true，结果已通过 onResult 回调；
     * 网络/响应/解析异常返回 false，由调用方回退 GitHub API 直连。
     */
    private fun trySelfHosted(
        currentVersion: String,
        onResult: (UpdateInfo?, Boolean, String?) -> Unit
    ): Boolean {
        return try {
            val conn = URL(SELF_HOSTED_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "aoooa-adb-updater")

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return false
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            conn.disconnect()

            val json = JSONObject(sb.toString())
            if (json.optInt("code", -1) != 0) return false
            val rawVer = json.optString("version", "").trim()
            val apkUrl = json.optString("apkUrl", "").trim()
            if (rawVer.isEmpty() || apkUrl.isEmpty()) return false

            latestRemoteVersion = rawVer
            if (isNewerVersion(rawVer, currentVersion)) {
                val body = json.optString("changelog", "").trim()
                val info = UpdateInfo(
                    tagName = json.optString("tagName", "").ifBlank { "v$rawVer" },
                    versionName = rawVer,
                    body = body.ifBlank { "修复已知问题并优化体验" },
                    downloadUrl = apkUrl,
                    fileSize = json.optLong("fileSize", 0L)
                )
                onResult(info, false, null)
            } else {
                onResult(null, true, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 语义化版本比对（如 2.6.0 比 2.5.9 新）
     */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        try {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * 使用系统 DownloadManager 下载并在完成后调起安装
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(info.downloadUrl)
            val fileName = "aoooa-adb_${info.tagName}.apk"

            // 存储在应用专属外部缓存下载目录中（无需任何危险权限）
            val targetFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = DownloadManager.Request(uri).apply {
                setTitle("aoooa-adb ${info.tagName}")
                setDescription("正在下载新版本安装包...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(targetFile))
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = dm.enqueue(request)
            AdbManager.log("已启动系统下载服务 (ID: $downloadId)，可在通知栏查看进度")

            // 注册系统广播监听下载完成事件
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            ctx.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        // 拉起系统安装器
                        installApk(ctx, targetFile)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            AdbManager.log("调用系统下载服务失败: ${e.message}")
        }
    }

    /**
     * 通过 FileProvider 拉起系统安装器
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            // 降级使用普通 Uri
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                AdbManager.log("自动调起安装失败，请手动打开目录安装")
            }
        }
    }
}
