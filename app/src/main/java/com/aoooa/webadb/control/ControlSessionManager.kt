package com.aoooa.webadb.control

import android.content.Context
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.Prefs
import com.aoooa.webadb.adb.AdbConnection
import com.aoooa.webadb.ui.control.ControlDisplayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 控制模式真投屏会话编排。
 * AdbConnection 仅调用其最小增量 API（pushBytes / openRawStream / writeRawStream）。
 * 协议与解码在本包新文件中完成，不改写认证/shell/logcat。
 */
enum class ControlSessionPhase {
    IDLE,
    PREPARING,
    RUNNING,
    STOPPING,
    ERROR
}

data class ControlSessionConfig(
    val maxSize: Int,
    val halfScreen: Boolean,
    val allowControl: Boolean = true
) {
    val displayMode: ControlDisplayMode
        get() = if (halfScreen) ControlDisplayMode.HALF else ControlDisplayMode.FULL_COMPAT

    val videoBitRate: Int
        get() = when (maxSize) {
            480 -> 2_000_000
            1080 -> 8_000_000
            else -> 4_000_000
        }
}

object ControlSessionManager {
    val phase = mutableStateOf(ControlSessionPhase.IDLE)
    val lastError = mutableStateOf("")
    val activeConfig = mutableStateOf<ControlSessionConfig?>(null)
    val videoWidth = mutableIntStateOf(0)
    val videoHeight = mutableIntStateOf(0)
    val remoteDeviceName = mutableStateOf("")
    val frameTick = mutableIntStateOf(0)

    private val mutex = Mutex()
    private val decoder = ScrcpyVideoDecoder(
        onError = { msg -> AdbManager.debugLog("[ControlDecode] $msg") },
        onFrame = { frameTick.intValue = frameTick.intValue + 1 }
    )

    @Volatile private var scid: Int = 0
    @Volatile private var videoLocalId: Int = 0
    @Volatile private var controlLocalId: Int = 0
    @Volatile private var serverShellLocalId: Int = 0
    private var demuxer: ScrcpyVideoDemuxer? = null
    private val serverAlive = AtomicBoolean(false)
    private val controlReady = AtomicBoolean(false)
    private val videoQueue = LinkedBlockingQueue<ByteArray>(256)

    val isBusy: Boolean
        get() = phase.value == ControlSessionPhase.PREPARING ||
            phase.value == ControlSessionPhase.STOPPING

    val isRunning: Boolean
        get() = phase.value == ControlSessionPhase.RUNNING

    val allowControlNow: Boolean
        get() = activeConfig.value?.allowControl == true && controlReady.get()

    fun attachSurface(surface: Surface?) {
        decoder.attachSurface(surface)
    }

    suspend fun start(context: Context, config: ControlSessionConfig): Boolean = mutex.withLock {
        if (phase.value == ControlSessionPhase.PREPARING || phase.value == ControlSessionPhase.RUNNING) {
            return false
        }
        if (!AdbManager.connected.value || AdbManager.isFastbootMode.value) {
            phase.value = ControlSessionPhase.ERROR
            lastError.value = if (AdbManager.isFastbootMode.value) "fastboot" else "disconnected"
            return false
        }

        Prefs.controlMaxSize = config.maxSize
        Prefs.controlHalfScreen = config.halfScreen
        Prefs.controlAllowControl = config.allowControl
        activeConfig.value = config
        lastError.value = ""
        videoWidth.intValue = 0
        videoHeight.intValue = 0
        remoteDeviceName.value = ""
        phase.value = ControlSessionPhase.PREPARING

        return withContext(Dispatchers.IO) {
            try {
                val conn = AdbManager.connection
                    ?: throw IllegalStateException("no_connection")

                // 控制会话期间尽量少被 shell/logcat 抢带宽
                try {
                    AdbManager.closeInteractiveShell()
                } catch (_: Exception) {
                }

                ensureServerPushed(context, conn)
                cleanupRemoteStale(conn)

                scid = Random.nextInt(1, 0x7FFFFFFF)
                val sock = ScrcpyProtocol.abstractSocketName(scid)
                val cmd = ScrcpyProtocol.buildServerCommand(
                    scid = scid,
                    maxSize = config.maxSize,
                    videoBitRate = config.videoBitRate,
                    controlEnabled = true // server 侧始终开 control 通道；客户端只读时不写
                )

                decoder.start()
                demuxer = ScrcpyVideoDemuxer(
                    expectDummyByte = true,
                    onSessionSize = { w, h ->
                        videoWidth.intValue = w
                        videoHeight.intValue = h
                        decoder.updateSize(w, h)
                        AdbManager.debugLog("[Control] video size ${w}x${h}")
                    },
                    onCodecId = { codec ->
                        AdbManager.debugLog("[Control] codec=0x%08X".format(codec))
                    },
                    onMediaPacket = { pts, isConfig, isKey, payload ->
                        // PTS 单位：scrcpy 原始计数，按微秒近似投喂即可
                        decoder.feed(isConfig, isKey, payload, pts)
                    },
                    onDeviceName = { name ->
                        remoteDeviceName.value = name
                        AdbManager.debugLog("[Control] device=$name")
                    },
                    onError = { err ->
                        AdbManager.debugLog("[Control] demux: $err")
                    }
                )

                // tunnel_forward=true：server 先 listen，再由本端 connect（先启动 server，再 OPEN abstract）
                serverShellLocalId = conn.openRawStream("shell:$cmd", onBytes = { bytes ->
                    val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        AdbManager.debugLog("[scrcpy-server] $text")
                        if (text.contains("ERROR", ignoreCase = true) ||
                            text.contains("Exception", ignoreCase = true) ||
                            text.contains("ERROR:", ignoreCase = true)
                        ) {
                            lastError.value = text.take(180)
                        }
                    }
                    serverAlive.set(true)
                }, onClosed = {
                    serverAlive.set(false)
                    AdbManager.debugLog("[scrcpy-server] process closed")
                })
                if (serverShellLocalId == 0) throw IllegalStateException("start_server_failed")
                // 给 server 一点时间 bind abstract socket
                delay(400)

                videoLocalId = conn.openRawStream("localabstract:$sock", onBytes = { bytes ->
                    try {
                        demuxer?.accept(bytes)
                    } catch (t: Throwable) {
                        AdbManager.debugLog("[Control] video accept: ${t.message}")
                    }
                }, onClosed = {
                    AdbManager.debugLog("[Control] video stream closed")
                    if (phase.value == ControlSessionPhase.RUNNING) {
                        lastError.value = "video_closed"
                    }
                })
                if (videoLocalId == 0) throw IllegalStateException("open_video_failed")
                if (!conn.awaitRawStreamReady(videoLocalId, 12_000)) {
                    throw IllegalStateException("video_handshake_timeout")
                }

                // audio=false 时顺序：video → control
                controlLocalId = conn.openRawStream("localabstract:$sock", onBytes = { _ ->
                    // device->client 剪贴板等，首版忽略
                }, onClosed = {
                    controlReady.set(false)
                    AdbManager.debugLog("[Control] control stream closed")
                })
                if (controlLocalId == 0) throw IllegalStateException("open_control_failed")
                if (!conn.awaitRawStreamReady(controlLocalId, 8_000)) {
                    throw IllegalStateException("control_handshake_timeout")
                }
                controlReady.set(true)

                // 等首个尺寸（最多数秒）；没有尺寸也能先 RUNNING，画面待 config 帧
                var wait = 0
                while (wait < 40 && videoWidth.intValue <= 0) {
                    delay(100)
                    wait++
                }

                if (!AdbManager.connected.value) throw IllegalStateException("disconnected")

                phase.value = ControlSessionPhase.RUNNING
                AdbManager.log(
                    "控制模式已连接：${config.maxSize}p / " +
                        "${if (config.halfScreen) "HALF" else "FULL"} / " +
                        "${if (config.allowControl) "可控" else "只读"}"
                )
                true
            } catch (e: Exception) {
                AdbManager.debugLog("ControlSession start failed: ${e.message}")
                lastError.value = e.message ?: "start_failed"
                hardCleanupIo()
                phase.value = ControlSessionPhase.ERROR
                activeConfig.value = null
                false
            }
        }
    }

    suspend fun stop(reason: String = "user") = mutex.withLock {
        if (phase.value == ControlSessionPhase.IDLE) return
        if (phase.value == ControlSessionPhase.STOPPING) return
        phase.value = ControlSessionPhase.STOPPING
        withContext(Dispatchers.IO) {
            hardCleanupIo()
            activeConfig.value = null
            phase.value = ControlSessionPhase.IDLE
            AdbManager.log("控制模式会话已结束（$reason）")
        }
    }

    fun injectNavHome() = injectKey(ScrcpyProtocol.KEYCODE_HOME)
    fun injectNavBack() = injectKey(ScrcpyProtocol.KEYCODE_BACK)
    fun injectNavRecents() = injectKey(ScrcpyProtocol.KEYCODE_APP_SWITCH)

    fun injectKey(keycode: Int) {
        if (!allowControlNow) return
        val conn = AdbManager.connection ?: return
        val id = controlLocalId
        if (id == 0) return
        for (pkt in ScrcpyProtocol.injectKeyClick(keycode)) {
            conn.writeRawStream(id, pkt)
        }
    }

    fun injectTouch(action: Int, x: Int, y: Int) {
        if (!allowControlNow) return
        val w = videoWidth.intValue
        val h = videoHeight.intValue
        if (w <= 0 || h <= 0) return
        val conn = AdbManager.connection ?: return
        val id = controlLocalId
        if (id == 0) return
        val pkt = ScrcpyProtocol.injectTouch(action, x, y, w, h)
        conn.writeRawStream(id, pkt)
    }

    private fun ensureServerPushed(context: Context, conn: AdbConnection) {
        val assetName = ScrcpyProtocol.SERVER_ASSET_NAME
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        if (bytes.isEmpty()) throw IllegalStateException("server_asset_empty")

        // 大小核对，不同则推送
        val remote = ScrcpyProtocol.SERVER_REMOTE_PATH
        val sizeOut = conn.shell("toybox stat -c %s $remote 2>/dev/null || stat -c %s $remote 2>/dev/null || wc -c < $remote 2>/dev/null")
        val remoteSize = sizeOut.trim().lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: -1L
        if (remoteSize != bytes.size.toLong()) {
            AdbManager.log("正在推送 scrcpy-server ${bytes.size}B …")
            val ok = conn.pushBytes(bytes, remote) { p ->
                if ((p * 100).toInt() % 25 == 0) {
                    AdbManager.debugLog("[Control] push ${(p * 100).toInt()}%")
                }
            }
            if (!ok) throw IllegalStateException("push_server_failed")
            // 确认
            val check = conn.shell("ls -l $remote")
            AdbManager.debugLog("[Control] push done: $check")
        } else {
            AdbManager.debugLog("[Control] server already present (${remoteSize}B)")
        }
    }

    private fun cleanupRemoteStale(conn: AdbConnection) {
        // best-effort：干掉残留 scrcpy server，避免 abstract 名冲突
        try {
            conn.shell("pkill -f com.genymobile.scrcpy.Server 2>/dev/null; pkill -f scrcpy.Server 2>/dev/null; true")
        } catch (_: Exception) {
        }
    }

    private fun hardCleanupIo() {
        val conn = AdbManager.connection
        try {
            decoder.stop()
        } catch (_: Exception) {
        }
        demuxer?.reset()
        demuxer = null
        controlReady.set(false)
        videoQueue.clear()

        if (conn != null) {
            if (videoLocalId != 0) {
                try { conn.closeRawStream(videoLocalId) } catch (_: Exception) {}
            }
            if (controlLocalId != 0) {
                try { conn.closeRawStream(controlLocalId) } catch (_: Exception) {}
            }
            if (serverShellLocalId != 0) {
                try { conn.closeRawStream(serverShellLocalId) } catch (_: Exception) {}
            }
            try {
                cleanupRemoteStale(conn)
            } catch (_: Exception) {
            }
        }
        videoLocalId = 0
        controlLocalId = 0
        serverShellLocalId = 0
        scid = 0
        videoWidth.intValue = 0
        videoHeight.intValue = 0
    }
}
