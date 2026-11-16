package com.aoooa.adb.control

import android.content.Context
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.control.ControlDisplayMode
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.control
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 投屏会话状态定义。
 * 会话管理器协调服务传输、ADB 通道建立、媒体解码及资源释放。
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
    val allowControl: Boolean = true,
    val audioEnabled: Boolean = false
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
    /** 勾选音频后若设备不支持/通道失败，给出 UI 红色提示；投屏仍继续。 */
    val audioWarning = mutableStateOf("")

    private val mutex = Mutex()
    private val monitorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var monitorJob: kotlinx.coroutines.Job? = null
    @Volatile private var sessionConnection: AdbConnection? = null
    @Volatile private var sessionToken: Any? = null
    private val decoder = ScrcpyVideoDecoder(
        onError = { msg -> AdbManager.debugLog("[ControlDecode] $msg") },
        onFrame = { frameTick.intValue = frameTick.intValue + 1 },
        diagnose = { event -> AdbManager.debugLog("[ControlDecode] $event") },
        onSizeChanged = { w, h ->
            videoWidth.intValue = w
            videoHeight.intValue = h
            AdbManager.debugLog("[Control] decoded video size ${w}x${h}")
        }
    )
    private val audioDecoder = ScrcpyAudioDecoder(
        onError = { msg ->
            AdbManager.debugLog("[ControlAudio] $msg")
            if (sessionToken != null) {
                if (audioWarning.value.isBlank()) AdbManager.log(I18n.current.control.audioFailed)
                audioWarning.value = "audio_failed"
            }
        }
    )

    @Volatile private var scid: Int = 0
    @Volatile private var videoLocalId: Int = 0
    @Volatile private var audioLocalId: Int = 0
    @Volatile private var controlLocalId: Int = 0
    @Volatile private var controlSender: ControlMessageSender? = null
    @Volatile private var serverShellLocalId: Int = 0
    private var demuxer: ScrcpyVideoDemuxer? = null
    private var audioDemuxer: ScrcpyAudioDemuxer? = null
    private val serverAlive = AtomicBoolean(false)
    private val controlReady = AtomicBoolean(false)
    private val audioReady = AtomicBoolean(false)
    private val videoQueue = LinkedBlockingQueue<ByteArray>(256)

    val isBusy: Boolean
        get() = phase.value == ControlSessionPhase.PREPARING ||
            phase.value == ControlSessionPhase.STOPPING

    val isRunning: Boolean
        get() = phase.value == ControlSessionPhase.RUNNING

    val allowControlNow: Boolean
        get() = activeConfig.value?.allowControl == true && controlReady.get()

    val allowAudioNow: Boolean
        get() = activeConfig.value?.audioEnabled == true && audioReady.get()

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
        Prefs.controlAudioEnabled = config.audioEnabled
        activeConfig.value = config
        lastError.value = ""
        audioWarning.value = ""
        videoWidth.intValue = 0
        videoHeight.intValue = 0
        remoteDeviceName.value = ""
        audioReady.set(false)
        phase.value = ControlSessionPhase.PREPARING

        return withContext(Dispatchers.IO) {
            try {
                val conn = AdbManager.connection
                    ?: throw IllegalStateException("no_connection")

                // 关闭交互式 Shell，减少投屏期间同一 ADB 连接上的并行数据传输。
                try {
                    AdbManager.closeInteractiveShell()
                } catch (_: Exception) {
                }

                hardCleanupIo()
                sessionConnection = conn
                val token = Any()
                sessionToken = token
                ensureServerPushed(context, conn)

                scid = Random.nextInt(1, 0x7FFFFFFF)
                val sock = ScrcpyProtocol.abstractSocketName(scid)
                val cmd = ScrcpyProtocol.buildServerCommand(
                    scid = scid,
                    maxSize = config.maxSize,
                    videoBitRate = config.videoBitRate,
                    controlEnabled = true, // server 侧始终开 control 通道；客户端只读时不写
                    audioEnabled = config.audioEnabled,
                    audioCodec = "aac"
                )

                decoder.start()
                if (config.audioEnabled) {
                    audioDecoder.start()
                }
                demuxer = ScrcpyVideoDemuxer(
                    expectDummyByte = true,
                    onSessionSize = { w, h ->
                        if (!ScrcpyProtocol.isPlausibleVideoSize(w, h)) {
                            AdbManager.debugLog("[Control] ignore bad size ${w}x${h}")
                            return@ScrcpyVideoDemuxer
                        }
                        videoWidth.intValue = w
                        videoHeight.intValue = h
                        decoder.updateSize(w, h)
                        AdbManager.debugLog("[Control] video size ${w}x${h}")
                    },
                    onCodecId = { codec ->
                        AdbManager.debugLog("[Control] codec=0x%08X".format(codec))
                    },
                    onMediaPacket = { pts, isConfig, isKey, payload ->
                        // 未获取有效分辨率前不送帧，避免 MediaCodec 初始化异常
                        if (videoWidth.intValue <= 0 || videoHeight.intValue <= 0) return@ScrcpyVideoDemuxer
                        if (payload.isEmpty()) return@ScrcpyVideoDemuxer
                        decoder.feed(isConfig, isKey, payload, pts)
                    },
                    onDeviceName = { name ->
                        remoteDeviceName.value = name
                        AdbManager.debugLog("[Control] device=$name")
                    },
                    onError = { err ->
                        AdbManager.debugLog("[Control] demux: $err")
                        // 协议校验失败时更新错误状态
                        if (err.startsWith("bad_packet_size") ||
                            err.startsWith("unsupported_codec") ||
                            err.startsWith("bad_init_size")
                        ) {
                            lastError.value = err
                        }
                    }
                )

                // tunnel_forward=true：server 先 listen，再由本端 connect（先启动 server，再 OPEN abstract）
                AdbManager.log(I18n.current.logCtrlStartServer)
                serverAlive.set(false)
                serverShellLocalId = conn.openRawStream("shell:$cmd", onBytes = server@{ bytes ->
                    if (sessionToken !== token) return@server
                    val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        AdbManager.debugLog("[scrcpy-server] $text")
                        // 关键错误也抬到界面，方便同机排查
                        if (text.contains("ERROR", ignoreCase = true) ||
                            text.contains("Exception", ignoreCase = true) ||
                            text.contains("AppProcess", ignoreCase = true)
                        ) {
                            lastError.value = text.trim().take(180)
                            AdbManager.log(I18n.current.control.startFailed)
                        }
                    }
                    serverAlive.set(true)
                }, onClosed = {
                    if (sessionToken === token) serverAlive.set(false)
                    AdbManager.debugLog("[scrcpy-server] process closed")
                })
                if (serverShellLocalId == 0) {
                    throw IllegalStateException(I18n.current.ctrlStartServerFailed)
                }
                // 等 server 起来一点；同机 127.0.0.1 时 bind 可能更慢
                var waitServer = 0
                while (waitServer < 20 && !serverAlive.get()) {
                    delay(100)
                    waitServer++
                }
                delay(300)

                if (!conn.isAuthenticated || !AdbManager.connected.value) {
                    throw IllegalStateException(I18n.current.ctrlOpenVideoFailed)
                }

                val videoOnBytes: (ByteArray) -> Unit = video@{ bytes ->
                    if (sessionToken !== token) return@video
                    try {
                        demuxer?.accept(bytes)
                    } catch (t: Throwable) {
                        AdbManager.debugLog("[Control] video accept: ${t.message}")
                    }
                }
                val videoOnClosed: () -> Unit = {
                    AdbManager.debugLog("[Control] video stream closed")
                    if (sessionToken === token && phase.value == ControlSessionPhase.RUNNING) {
                        lastError.value = "video_closed"
                    }
                }

                // 重试打开视频通道：server abstract socket 可能尚未就绪
                AdbManager.log(String.format(I18n.current.logCtrlConnectVideo, sock))
                videoLocalId = conn.openRawStreamWithRetry(
                    service = "localabstract:$sock",
                    onBytes = videoOnBytes,
                    onClosed = videoOnClosed,
                    attempts = 10,
                    perAttemptTimeoutMs = 1200L,
                    gapMs = 300L
                )
                if (videoLocalId == 0 && conn.isAuthenticated) {
                    // 少数机型兼容：再试 local: 前缀
                    AdbManager.debugLog("[Control] localabstract 失败，尝试 local:$sock")
                    videoLocalId = conn.openRawStreamWithRetry(
                        service = "local:$sock",
                        onBytes = videoOnBytes,
                        onClosed = videoOnClosed,
                        attempts = 6,
                        perAttemptTimeoutMs = 1200L,
                        gapMs = 300L
                    )
                }
                if (videoLocalId == 0) {
                    val hint = when {
                        !conn.isAuthenticated || !AdbManager.connected.value -> I18n.current.ctrlAdbDisconnected
                        lastError.value.isNotBlank() -> lastError.value
                        else -> I18n.current.ctrlSocketNotReady
                    }
                    throw IllegalStateException("open_video_failed($hint)")
                }

                // tunnel_forward 顺序：video → (audio?) → control
                if (config.audioEnabled) {
                    AdbManager.log(I18n.current.logCtrlConnectAudio)
                    val audioFailed = AtomicBoolean(false)
                    audioDemuxer = ScrcpyAudioDemuxer(
                        onCodecId = { codec ->
                            AdbManager.debugLog("[Control] audio codec=0x%08X".format(codec))
                        },
                        onMediaPacket = { pts, isConfig, _, payload ->
                            if (payload.isEmpty()) return@ScrcpyAudioDemuxer
                            audioDecoder.feed(isConfig, payload, pts)
                        },
                        onDisabled = {
                            audioFailed.set(true)
                            audioReady.set(false)
                            audioWarning.value = "unsupported"
                            AdbManager.debugLog("[Control] audio stream disabled by device")
                        },
                        onError = { err ->
                            audioFailed.set(true)
                            audioReady.set(false)
                            audioWarning.value = err
                            AdbManager.debugLog("[Control] audio demux: $err")
                        }
                    )
                    val audioOnBytes: (ByteArray) -> Unit = audio@{ bytes ->
                        if (sessionToken !== token) return@audio
                        try {
                            audioDemuxer?.accept(bytes)
                        } catch (t: Throwable) {
                            AdbManager.debugLog("[Control] audio accept: ${t.message}")
                        }
                    }
                    audioLocalId = conn.openRawStreamWithRetry(
                        service = "localabstract:$sock",
                        onBytes = audioOnBytes,
                        onClosed = {
                            if (sessionToken === token) audioReady.set(false)
                            AdbManager.debugLog("[Control] audio stream closed")
                        },
                        attempts = 8,
                        perAttemptTimeoutMs = 1200L,
                        gapMs = 250L
                    )
                    if (audioLocalId == 0) {
                        // 音频连接失败时释放音频组件，继续建立控制通道。
                        audioDemuxer?.reset()
                        audioDemuxer = null
                        try { audioDecoder.stop() } catch (_: Exception) {}
                        audioWarning.value = "unsupported"
                        AdbManager.log(I18n.current.logCtrlAudioDegraded)
                    } else if (!audioFailed.get()) {
                        audioReady.set(true)
                    }
                }

                AdbManager.log(I18n.current.logCtrlConnectControl)
                controlLocalId = conn.openRawStreamWithRetry(
                    service = "localabstract:$sock",
                    onBytes = { _ -> },
                    onClosed = {
                        if (sessionToken === token) controlReady.set(false)
                        AdbManager.debugLog("[Control] control stream closed")
                    },
                    attempts = 8,
                    perAttemptTimeoutMs = 1200L,
                    gapMs = 250L
                )
                if (controlLocalId == 0) {
                    throw IllegalStateException("open_control_failed")
                }
                val sessionControlId = controlLocalId
                controlSender = ControlMessageSender(
                    write = { packet -> conn.writeRawStreamAwait(sessionControlId, packet, timeoutMs = 5_000L) },
                    diagnose = { event -> AdbManager.debugLog("[Control send] localId=$sessionControlId $event") },
                    onFailure = {
                        if (controlLocalId == sessionControlId && AdbManager.connection === conn &&
                            phase.value == ControlSessionPhase.RUNNING
                        ) {
                            controlReady.set(false)
                            lastError.value = "control_send_failed"
                            AdbManager.log(I18n.current.control.controlSendFailed)
                            conn.closeRawStream(sessionControlId)
                        }
                        AdbManager.debugLog("[Control] control sender stopped after congestion or write failure")
                    }
                )
                controlReady.set(true)

                // 等首个尺寸（最多数秒）；没有尺寸也能先 RUNNING，画面待 config 帧
                var wait = 0
                while (wait < 40 && videoWidth.intValue <= 0) {
                    delay(100)
                    wait++
                }

                ControlConnectionHealth.error(
                    AdbManager.connected.value && AdbManager.connection === conn && conn.isAuthenticated,
                    conn.awaitRawStreamReady(videoLocalId, 0L),
                    conn.awaitRawStreamReady(controlLocalId, 0L)
                )?.let { throw IllegalStateException(it) }
                phase.value = ControlSessionPhase.RUNNING
                monitorJob = monitorScope.launch {
                    while (sessionToken === token) {
                        delay(250L)
                        val failure = ControlConnectionHealth.error(
                            AdbManager.connection === conn && conn.isAuthenticated,
                            conn.awaitRawStreamReady(videoLocalId, 0L),
                            conn.awaitRawStreamReady(controlLocalId, 0L)
                        )
                        if (failure != null) {
                            mutex.withLock {
                                if (sessionToken === token && phase.value == ControlSessionPhase.RUNNING) {
                                    if (failure != "control_closed" || lastError.value != "control_send_failed") {
                                        lastError.value = failure
                                    }
                                    phase.value = ControlSessionPhase.ERROR
                                    hardCleanupIo()
                                    activeConfig.value = null
                                }
                            }
                            break
                        }
                    }
                }
                AdbManager.log(
                    String.format(
                            I18n.current.ctrlConnectedSummary,
                            config.maxSize,
                            if (config.halfScreen) "HALF" else "FULL",
                            if (config.allowControl) I18n.current.ctrlModeControllable else I18n.current.ctrlModeReadOnly,
                            if (config.audioEnabled && audioReady.get()) I18n.current.ctrlAudioOn
                            else if (config.audioEnabled) I18n.current.ctrlAudioDegraded
                            else I18n.current.ctrlAudioOff
                        )
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

    fun captureSession(): Any? = sessionToken

    suspend fun stop(reason: String = "user", expectedSession: Any? = captureSession()) = mutex.withLock {
        if (sessionToken !== expectedSession) return
        if (phase.value == ControlSessionPhase.IDLE) return
        if (phase.value == ControlSessionPhase.STOPPING) return
        phase.value = ControlSessionPhase.STOPPING
        withContext(Dispatchers.IO) {
            hardCleanupIo()
            activeConfig.value = null
            phase.value = ControlSessionPhase.IDLE
            AdbManager.log(String.format(I18n.current.logCtrlSessionEnded, reason))
        }
    }

    fun injectNavHome() = injectKey(ScrcpyProtocol.KEYCODE_HOME)
    fun injectNavBack() = injectKey(ScrcpyProtocol.KEYCODE_BACK)
    fun injectNavRecents() = injectKey(ScrcpyProtocol.KEYCODE_APP_SWITCH)

    fun injectKey(keycode: Int) {
        if (!allowControlNow) return
        if (!ScrcpyProtocol.isPlausibleVideoSize(videoWidth.intValue, videoHeight.intValue) &&
            videoWidth.intValue != 0
        ) {
            // 尺寸异常时跳过触控注入
            AdbManager.debugLog("[Control] skip key: invalid video size")
            return
        }
        controlSender?.send(ScrcpyProtocol.injectKeyClick(keycode))
    }

    fun injectTouch(
        action: Int,
        x: Int,
        y: Int,
        pointerId: Long = ScrcpyProtocol.POINTER_ID_FINGER,
        width: Int = videoWidth.intValue,
        height: Int = videoHeight.intValue
    ) {
        if (!allowControlNow) return
        if (!ScrcpyProtocol.isPlausibleVideoSize(width, height)) return
        controlSender?.send(listOf(ScrcpyProtocol.injectTouch(action, x, y, width, height, pointerId)))
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
            AdbManager.log(String.format(I18n.current.logCtrlPushServer, bytes.size))
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

    private fun hardCleanupIo() {
        sessionToken = null
        monitorJob?.cancel()
        monitorJob = null
        controlSender?.close()
        controlSender = null
        val conn = sessionConnection
        sessionConnection = null
        try {
            decoder.stop()
        } catch (_: Exception) {
        }
        try {
            audioDecoder.stop()
        } catch (_: Exception) {
        }
        demuxer?.reset()
        demuxer = null
        audioDemuxer?.reset()
        audioDemuxer = null
        controlReady.set(false)
        audioReady.set(false)
        videoQueue.clear()

        if (conn != null) {
            if (videoLocalId != 0) {
                try { conn.closeRawStream(videoLocalId) } catch (_: Exception) {}
            }
            if (audioLocalId != 0) {
                try { conn.closeRawStream(audioLocalId) } catch (_: Exception) {}
            }
            if (controlLocalId != 0) {
                try { conn.closeRawStream(controlLocalId) } catch (_: Exception) {}
            }
            if (serverShellLocalId != 0) {
                try { conn.closeRawStream(serverShellLocalId) } catch (_: Exception) {}
            }
        }
        videoLocalId = 0
        audioLocalId = 0
        controlLocalId = 0
        serverShellLocalId = 0
        scid = 0
        videoWidth.intValue = 0
        videoHeight.intValue = 0
    }
}
