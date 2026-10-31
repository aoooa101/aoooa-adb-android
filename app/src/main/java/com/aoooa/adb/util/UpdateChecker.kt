package com.aoooa.adb.util

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 更新包下载与校验状态。
 *
 * 由 [UpdateChecker.downloadState] 对外暴露，界面通过 StateFlow 订阅。
 * [Downloading.percent] 为 -1 表示总大小未知，此时应展示不确定进度条。
 */
sealed interface UpdateDownloadState {

    /** 无进行中的下载任务。 */
    data object Idle : UpdateDownloadState

    /** 下载进行中。total 取自 DownloadManager 的实际总字节数。 */
    data class Downloading(
        val percent: Int,
        val downloaded: Long,
        val total: Long
    ) : UpdateDownloadState

    /** 下载完成，正在计算本地文件 SHA-256 并与远端哈希比对。 */
    data object Verifying : UpdateDownloadState

    /** 校验通过，文件可直接安装。 */
    data class Ready(val file: File) : UpdateDownloadState

    /**
     * 任务失败。
     * @param reason 失败原因标识，用于界面展示
     * @param hashMismatch true 表示哈希不一致或文件不可读，需提示安装包损坏
     */
    data class Failed(
        val reason: String,
        val hashMismatch: Boolean = false
    ) : UpdateDownloadState
}

/**
 * 应用更新检测与下载安装管理
 */
object UpdateChecker {

    private const val RELEASES_API = "https://api.github.com/repos/aoooa101/aoooa-adb-android/releases/latest"

    /** 自建更新检查接口地址（数据源为 GitHub releases.atom，不消耗 GitHub API 配额） */
    private const val SELF_HOSTED_API = "https://aoooa.dpdns.org/app/check"

    private val executor = Executors.newSingleThreadExecutor()

    /** 下载进度轮询间隔（毫秒） */
    private const val PROGRESS_INTERVAL_MS = 500L

    /** 下载进度轮询的世代标识：递增即令旧轮询线程退出 */
    @Volatile
    private var pollToken = 0L

    /** 下载完成广播接收器，全局仅注册一次，避免重复注册与泄漏 */
    @Volatile
    private var completionReceiver: BroadcastReceiver? = null

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)

    /** 当前下载与校验状态，供界面订阅 */
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    /** 记录最新检测到的远端版本号（如 "2.7.2"） */
    var latestRemoteVersion: String? = null

    /**
     * 计算指定文件的 SHA-256 哈希值（小写十六进制）。
     *
     * 采用 64KB 分块流式读取，避免将整个 APK 载入内存。
     * @return 哈希字符串；文件不可读或计算异常时返回 null
     */
    fun sha256OfFile(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
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
     * 计算本地已安装 APK 的 SHA-256 哈希值
     */
    fun getLocalApkSha256(context: Context): String? {
        return try {
            val apkPath = context.applicationInfo.sourceDir ?: return null
            sha256OfFile(File(apkPath))
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
     * 判定是否触发升级弹窗。
     *
     * 1. 两端哈希均有效且相同：判定为同一构建，不升级；
     * 2. 两端哈希均有效但不同：视为同版本的重新构建，远端版本不低于本地即提示升级；
     * 3. 任一哈希缺失，无法判定构建是否相同：仅在远端版本严格高于本地时提示升级，
     *    避免远端版本等于本地时反复弹出同一版本的更新提示。
     */
    fun shouldTriggerUpdate(
        remoteVer: String,
        currentVersion: String,
        remoteHash: String?,
        localHash: String?
    ): Boolean {
        val cmp = compareVersions(remoteVer, currentVersion)
        val hasBothHashes = !remoteHash.isNullOrBlank() && !localHash.isNullOrBlank()
        if (hasBothHashes) {
            if (remoteHash.equals(localHash, ignoreCase = true)) return false
            return cmp >= 0
        }
        return cmp > 0
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
                lower.contains("arm64") || lower.contains("aarch64") -> arm64Asset = asset
                lower.contains("armeabi") || lower.contains("v7a") || lower.contains("arm32") -> arm32Asset = asset
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

            // 通道一：自建更新接口，优先使用；返回成功即不再请求 GitHub API
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
     * 通道一：自建更新接口，解析 version/apkUrl/sha256/assets 字段
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
                    // 该接口的 assets 元素仅含 name/apkUrl/sha256，不含体积字段；
                    // 切换到非默认架构包后顶层 fileSize 不再适用，置 0 交由下载进度实测总长
                    if (mUrl.isNotEmpty() && mUrl != downloadUrl) {
                        downloadUrl = mUrl
                        fileSize = 0L
                    }
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
     * 使用系统 DownloadManager 下载更新包。
     *
     * 下载任务由系统服务托管，应用进程被回收后仍会继续；任务 ID、目标文件路径
     * 与期望哈希一并写入 Prefs，供 [resumePendingInstall] 在下次启动时恢复
     * 进度显示与安装流程。
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        val appContext = context.applicationContext
        try {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(info.downloadUrl)
            val fileName = "aoooa-adb_${info.tagName}.apk"

            // 存储在应用私有缓存目录（cache/apk_updates）中
            val updateDir = File(appContext.externalCacheDir ?: appContext.cacheDir, "apk_updates")
            if (!updateDir.exists()) {
                updateDir.mkdirs()
            }
            // 清空历史安装包，仅保留仍待安装的目标，确保单实例
            val pendingPath = Prefs.pendingUpdatePath
            updateDir.listFiles()?.forEach {
                if (it.absolutePath != pendingPath) runCatching { it.delete() }
            }

            val targetFile = File(updateDir, fileName)
            if (targetFile.exists()) runCatching { targetFile.delete() }

            val isZh = Prefs.lang == "zh"
            val request = DownloadManager.Request(uri).apply {
                setTitle("aoooa-adb ${info.tagName}")
                setDescription(if (isZh) "正在下载新版本安装包" else "Downloading update package")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(targetFile))
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = dm.enqueue(request)

            Prefs.pendingUpdateDownloadId = downloadId
            Prefs.pendingUpdatePath = targetFile.absolutePath
            Prefs.pendingUpdateHash = info.remoteHash.orEmpty()
            Prefs.pendingUpdateTag = info.tagName

            _downloadState.value = UpdateDownloadState.Downloading(
                percent = if (info.fileSize > 0L) 0 else -1,
                downloaded = 0L,
                total = info.fileSize.coerceAtLeast(0L)
            )

            ensureCompletionReceiver(appContext)
            startProgressPolling(appContext, downloadId)
            AdbManager.log("已启动系统下载服务 (ID: $downloadId)，可在通知栏查看进度")
        } catch (e: Exception) {
            _downloadState.value = UpdateDownloadState.Failed(e.message ?: "download_start_failed")
            AdbManager.log("调用系统下载服务失败: ${e.message}")
        }
    }

    /**
     * 恢复上次未完成的更新任务。
     *
     * 应用启动时调用：任务仍在进行则恢复进度轮询并重新挂载完成监听；
     * 任务已完成则直接进入哈希校验与安装流程。
     */
    fun resumePendingInstall(context: Context) {
        val appContext = context.applicationContext
        val downloadId = Prefs.pendingUpdateDownloadId
        if (downloadId <= 0L) return

        val path = Prefs.pendingUpdatePath
        if (path.isBlank()) {
            clearPendingUpdate()
            return
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            clearPendingUpdate()
            return
        }
        ensureCompletionReceiver(appContext)

        val snapshot = queryDownload(dm, downloadId)
        when {
            snapshot == null -> clearPendingUpdate()
            snapshot.status == DownloadManager.STATUS_RUNNING ||
                snapshot.status == DownloadManager.STATUS_PENDING -> {
                _downloadState.value = UpdateDownloadState.Downloading(
                    percent = percentOf(snapshot),
                    downloaded = snapshot.downloaded,
                    total = snapshot.total
                )
                startProgressPolling(appContext, downloadId)
            }
            snapshot.status == DownloadManager.STATUS_SUCCESSFUL -> {
                // 校验与安装器调起保持在调用线程（主线程）执行：一是 SHA-256 对
                // 十余 MB 文件耗时在百毫秒级，二是可沿用广播接收器的后台启动豁免
                handleDownloadFinished(appContext, downloadId)
            }
            else -> {
                AdbManager.log("上次更新任务未成功结束，已清除记录")
                clearPendingUpdate()
            }
        }
    }

    /** 注册全局唯一的下载完成接收器，重复调用不会重复注册。 */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureCompletionReceiver(context: Context) {
        if (completionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                // 仅处理与本地已记录下载任务 ID 一致的广播
                if (id <= 0L || id != Prefs.pendingUpdateDownloadId) return
                handleDownloadFinished(ctx, id)
            }
        }
        try {
            // 系统 DownloadManager 运行于独立系统进程，需声明 RECEIVER_EXPORTED 接收跨进程完成事件
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            completionReceiver = receiver
        } catch (_: Exception) {
        }
    }

    /**
     * 轮询下载进度并同步到状态流，任务进入终态后自动退出。
     *
     * 每次调用递增 [pollToken]，使上一轮轮询线程在下一个周期退出，避免多个线程同时写状态。
     */
    private fun startProgressPolling(context: Context, downloadId: Long) {
        val token = ++pollToken
        Thread({
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) return@Thread
            while (pollToken == token) {
                val snapshot = queryDownload(dm, downloadId) ?: return@Thread
                val running = snapshot.status == DownloadManager.STATUS_RUNNING ||
                    snapshot.status == DownloadManager.STATUS_PENDING
                if (!running) return@Thread
                _downloadState.value = UpdateDownloadState.Downloading(
                    percent = percentOf(snapshot),
                    downloaded = snapshot.downloaded,
                    total = snapshot.total
                )
                try {
                    Thread.sleep(PROGRESS_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "update-progress").apply { isDaemon = true }.start()
    }

    private data class DownloadSnapshot(
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val reason: Int
    )

    private fun queryDownload(dm: DownloadManager, downloadId: Long): DownloadSnapshot? {
        return try {
            dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                DownloadSnapshot(
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ),
                    total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ),
                    reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 计算进度百分比；总长度未知时返回 -1，由界面显示不确定进度。 */
    private fun percentOf(snapshot: DownloadSnapshot): Int =
        if (snapshot.total > 0L) {
            ((snapshot.downloaded * 100L) / snapshot.total).toInt().coerceIn(0, 100)
        } else {
            -1
        }

    /**
     * 处理下载完成事件：先确认任务状态成功，再校验文件哈希，全部通过后才拉起安装器。
     */
    private fun handleDownloadFinished(context: Context, downloadId: Long) {
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            failDownload("download_service_unavailable")
            return
        }

        val snapshot = queryDownload(dm, downloadId)
        if (snapshot == null || snapshot.status != DownloadManager.STATUS_SUCCESSFUL) {
            val detail = snapshot?.let { "status=${it.status},reason=${it.reason}" } ?: "task_missing"
            AdbManager.log("更新包下载未成功结束: $detail")
            failDownload(detail)
            return
        }

        val targetFile = File(Prefs.pendingUpdatePath)
        _downloadState.value = UpdateDownloadState.Verifying

        if (!targetFile.exists() || targetFile.length() <= 0L) {
            AdbManager.log("更新包文件不存在或为空: ${targetFile.absolutePath}")
            failDownload("file_missing")
            return
        }

        val expected = Prefs.pendingUpdateHash.trim()
        if (expected.isEmpty()) {
            // 远端未提供 SHA-256 时无法校验完整性，记录后按原流程安装
            AdbManager.log("远端未提供 SHA-256，跳过完整性校验")
        } else {
            val actual = sha256OfFile(targetFile)
            if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                AdbManager.log("更新包哈希不一致，已阻止安装")
                runCatching { targetFile.delete() }
                _downloadState.value =
                    UpdateDownloadState.Failed("hash_mismatch", hashMismatch = true)
                clearPendingUpdate()
                return
            }
        }

        _downloadState.value = UpdateDownloadState.Ready(targetFile)
        clearPendingUpdate()
        // 在发起校验的同一上下文内调起安装器：由广播接收器触发时处于后台启动豁免窗口，
        // 由前台恢复流程触发时应用本身在前台，两种情况均不会被系统拦截。
        installApk(appContext, targetFile)
    }

    private fun failDownload(reason: String) {
        _downloadState.value = UpdateDownloadState.Failed(reason)
        clearPendingUpdate()
    }

    /** 清除待安装任务记录，并终止正在运行的进度轮询。 */
    private fun clearPendingUpdate() {
        pollToken++
        Prefs.pendingUpdateDownloadId = 0L
        Prefs.pendingUpdatePath = ""
        Prefs.pendingUpdateHash = ""
        Prefs.pendingUpdateTag = ""
    }

    /**
     * 通过 FileProvider 拉起系统安装器
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            AdbManager.log("安装包不存在，无法调起安装器: ${apkFile.absolutePath}")
            return
        }
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
            AdbManager.log("FileProvider 调起安装失败: ${e.message}，尝试 file:// 方式")
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

    /**
     * 清理旧版本遗留在应用私有目录中的 Download 文件夹（一次性数据迁移）。
     */
    fun cleanupLegacyDownloadDir(context: Context) {
        if (Prefs.isLegacyDownloadCleaned) return
        executor.execute {
            try {
                val legacyDownloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (legacyDownloadDir != null && legacyDownloadDir.exists()) {
                    legacyDownloadDir.deleteRecursively()
                }
                Prefs.isLegacyDownloadCleaned = true
            } catch (_: Exception) {
                Prefs.isLegacyDownloadCleaned = true
            }
        }
    }

    /**
     * 清理应用私有缓存目录中的更新安装包。
     *
     * [Prefs.pendingUpdatePath] 指向的文件会被保留，避免与安装器读取文件的过程竞争。
     */
    fun cleanUpdateCache(context: Context) {
        executor.execute {
            try {
                val updateDir = File(context.externalCacheDir ?: context.cacheDir, "apk_updates")
                if (!updateDir.exists()) return@execute
                val pendingPath = Prefs.pendingUpdatePath
                updateDir.listFiles { _, name -> name.endsWith(".apk") }?.forEach {
                    if (it.absolutePath != pendingPath) runCatching { it.delete() }
                }
            } catch (_: Exception) {}
        }
    }
}
