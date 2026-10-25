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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 远程更新信息实体
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val body: String,
    val downloadUrl: String,
    val fileSize: Long,
    val remoteHash: String? = null
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
     * 计算本地已安装 APK 的 SHA-256 哈希值
     */
    fun getLocalApkSha256(context: Context): String? {
        return try {
            val apkPath = context.applicationInfo.sourceDir ?: return null
            val apkFile = File(apkPath)
            if (!apkFile.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 语义化版本比对
     * 返回 >0 表示 remote > local；=0 表示 remote == local；<0 表示 remote < local
     */
    fun compareVersions(remote: String, local: String): Int {
        if (remote.isBlank() && local.isBlank()) return 0
        if (remote.isBlank()) return -1
        if (local.isBlank()) return 1
        try {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r != l) return r.compareTo(l)
            }
        } catch (_: Exception) {}
        return 0
    }

    /**
     * 判定是否触发升级弹窗：
     * 1. 优先校验哈希：如果本地与远程哈希均有效且相同，判定为同一构建，不升级；
     * 2. 若哈希不一致：比对版本号，远程版本低于本地则不升级（防降级），等于或高于则提示升级。
     */
    fun shouldTriggerUpdate(
        remoteVer: String,
        currentVersion: String,
        remoteHash: String?,
        localHash: String?
    ): Boolean {
        val hasBothHashes = !remoteHash.isNullOrBlank() && !localHash.isNullOrBlank()
        if (hasBothHashes && remoteHash.equals(localHash, ignoreCase = true)) {
            return false
        }
        val cmp = compareVersions(remoteVer, currentVersion)
        return cmp >= 0
    }

    /**
     * 依据当前设备 CPU 架构优先匹配最适用的 APK 资产：
     * 64位设备优先匹配 arm64-v8a，未匹配则选 universal；
     * 32位设备优先匹配 armeabi-v7a，未匹配则选 universal；
     * 兜底匹配列表中的任意可用 APK。
     */
    private fun matchBestApkAsset(assets: JSONArray): JSONObject? {
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }
        var arm64Asset: JSONObject? = null
        var arm32Asset: JSONObject? = null
        var universalAsset: JSONObject? = null
        var fallbackAsset: JSONObject? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue

            if (fallbackAsset == null) fallbackAsset = asset

            val lower = name.lowercase()
            when {
                lower.contains("arm64") -> arm64Asset = asset
                lower.contains("armeabi") || lower.contains("v7a") || lower.contains("32") -> arm32Asset = asset
                lower.contains("universal") -> universalAsset = asset
            }
        }

        return if (isArm64) {
            arm64Asset ?: universalAsset ?: fallbackAsset
        } else {
            arm32Asset ?: universalAsset ?: fallbackAsset
        }
    }

    /**
     * 检查远程是否有新版本
     * @param context 应用上下文（用于计算本地已装 APK 哈希及调起下载）
     * @param currentVersion 当前本地版本号（如 "2.8.0"）
     * @param onResult 回调：(updateInfo, isLatest, errorMsg)
     */
    fun checkUpdate(
        context: Context,
        currentVersion: String,
        onResult: (UpdateInfo?, Boolean, String?) -> Unit
    ) {
        executor.execute {
            val localSha256 = getLocalApkSha256(context)

            // 通道一：自建更新接口（优先；Serv00 节点连通 GitHub 稳定，缓存 6 小时）
            if (trySelfHosted(currentVersion, localSha256, onResult)) return@execute

            // 通道二：GitHub API 直连（回退保底）
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

                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    var fileSize = 0L
                    var remoteSha256: String? = null

                    if (assets != null && assets.length() > 0) {
                        val matched = matchBestApkAsset(assets)
                        if (matched != null) {
                            downloadUrl = matched.optString("browser_download_url", "")
                            fileSize = matched.optLong("size", 0L)
                            val digest = matched.optString("digest", "").trim()
                            if (digest.isNotEmpty()) {
                                remoteSha256 = if (digest.startsWith("sha256:", ignoreCase = true)) {
                                    digest.substringAfter("sha256:").trim().lowercase()
                                } else {
                                    digest.lowercase()
                                }
                            }
                        }
                    }

                    val needUpdate = shouldTriggerUpdate(rawVer, currentVersion, remoteSha256, localSha256)
                    if (needUpdate && downloadUrl.isNotEmpty()) {
                        val info = UpdateInfo(
                            tagName = tagName.ifBlank { "v$rawVer" },
                            versionName = rawVer,
                            body = if (body.isNotBlank()) body else "修复已知问题并优化体验",
                            downloadUrl = downloadUrl,
                            fileSize = fileSize,
                            remoteHash = remoteSha256
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
     * 通道一：自建更新接口（Serv00 节点，数据源 releases.atom + expanded_assets）
     */
    private fun trySelfHosted(
        currentVersion: String,
        localSha256: String?,
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
            if (rawVer.isEmpty()) return false

            latestRemoteVersion = rawVer

            var downloadUrl = json.optString("apkUrl", "").trim()
            var fileSize = json.optLong("fileSize", 0L)
            var remoteSha256 = json.optString("sha256", "").trim().ifEmpty { null }

            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                val matched = matchBestApkAsset(assets)
                if (matched != null) {
                    val mUrl = matched.optString("apkUrl", "")
                    if (mUrl.isNotEmpty()) downloadUrl = mUrl
                    val mSize = matched.optLong("fileSize", 0L)
                    if (mSize > 0) fileSize = mSize
                    val mHash = matched.optString("sha256", "").trim()
                    if (mHash.isNotEmpty()) remoteSha256 = mHash
                }
            }

            if (downloadUrl.isEmpty()) return false

            val needUpdate = shouldTriggerUpdate(rawVer, currentVersion, remoteSha256, localSha256)
            if (needUpdate) {
                val body = json.optString("changelog", "").trim()
                val info = UpdateInfo(
                    tagName = json.optString("tagName", "").ifBlank { "v$rawVer" },
                    versionName = rawVer,
                    body = body.ifBlank { "修复已知问题并优化体验" },
                    downloadUrl = downloadUrl,
                    fileSize = fileSize,
                    remoteHash = remoteSha256
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
     * 使用系统 DownloadManager 下载并在完成后调起安装
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(info.downloadUrl)
            val fileName = "aoooa-adb_${info.tagName}.apk"

            // 存储在应用专属外部缓存下载目录中
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

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            ctx.unregisterReceiver(this)
                        } catch (_: Exception) {}

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
