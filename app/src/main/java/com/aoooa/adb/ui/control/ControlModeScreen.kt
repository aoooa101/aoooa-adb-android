package com.aoooa.adb.ui.control

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.aoooa.adb.ui.i18n.control
import com.aoooa.adb.ui.i18n.controlErrorMessage
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.control.ControlSessionConfig
import com.aoooa.adb.control.ControlSessionManager
import com.aoooa.adb.control.ControlSessionPhase
import com.aoooa.adb.control.ScrcpyProtocol
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * HALF = 画面半屏；FULL_COMPAT = 全屏占满（内部名，不对用户展示「兼容模式」）。
 * 退出逻辑两种布局相同：系统返回键点一次提示，再点一次退出。
 */
enum class ControlDisplayMode {
    HALF,
    FULL_COMPAT
}

@Composable
fun ControlModeScreen(
    s: Strings,
    modifier: Modifier = Modifier
) {
    val connected by AdbManager.connected
    val isFastboot by AdbManager.isFastbootMode
    val deviceName by AdbManager.deviceName
    val phase by ControlSessionManager.phase
    val lastError by ControlSessionManager.lastError
    val activeConfig by ControlSessionManager.activeConfig
    val remoteName by ControlSessionManager.remoteDeviceName

    var maxSize by remember { mutableIntStateOf(Prefs.controlMaxSize) }
    var halfScreen by remember { mutableStateOf(Prefs.controlHalfScreen) }
    var allowControl by remember { mutableStateOf(Prefs.controlAllowControl) }
    var audioEnabled by remember { mutableStateOf(Prefs.controlAudioEnabled) }
    var statusHint by remember { mutableStateOf("") }
    val audioWarning by ControlSessionManager.audioWarning
    var lastBackUptime by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val canStart = connected && !isFastboot
    val preparing = phase == ControlSessionPhase.PREPARING
    val running = phase == ControlSessionPhase.RUNNING
    val displayMode = activeConfig?.displayMode
        ?: if (halfScreen) ControlDisplayMode.HALF else ControlDisplayMode.FULL_COMPAT
    val controlEnabled = activeConfig?.allowControl != false

    LaunchedEffect(lastError, phase, s) {
        if (phase == ControlSessionPhase.ERROR) {
            statusHint = s.controlErrorMessage(lastError)
        }
    }

    fun toastPressAgain() {
        Toast.makeText(context, s.controlPressAgainToExit, Toast.LENGTH_SHORT).show()
    }

    fun handleExitBack() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackUptime < 2000L) {
            lastBackUptime = 0L
            scope.launch { ControlSessionManager.stop("back") }
        } else {
            lastBackUptime = now
            toastPressAgain()
        }
    }

    fun startSession() {
        if (!canStart || preparing || running) return
        statusHint = ""
        lastBackUptime = 0L
        scope.launch {
            val ok = ControlSessionManager.start(
                context = context.applicationContext,
                config = ControlSessionConfig(
                    maxSize = maxSize,
                    halfScreen = halfScreen,
                    allowControl = allowControl,
                    audioEnabled = audioEnabled
                )
            )
            if (!ok && ControlSessionManager.phase.value == ControlSessionPhase.ERROR) {
                statusHint = s.controlErrorMessage(ControlSessionManager.lastError.value)
            }
        }
    }

    BackHandler(enabled = preparing || (running && displayMode == ControlDisplayMode.HALF)) {
        handleExitBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!running || displayMode == ControlDisplayMode.HALF) {
            if (!running) {
                ControlSetupPane(
                    s = s,
                    connected = connected,
                    isFastboot = isFastboot,
                    deviceName = deviceName,
                    maxSize = maxSize,
                    halfScreen = halfScreen,
                    allowControl = allowControl,
                    audioEnabled = audioEnabled,
                    audioWarning = when {
                        !audioEnabled || audioWarning.isBlank() -> ""
                        audioWarning == "unsupported" -> s.controlAudioUnsupported
                        else -> s.control.audioFailed
                    },
                    preparing = preparing,
                    canStart = canStart,
                    statusHint = statusHint,
                    onMaxSizeChange = {
                        maxSize = it
                        Prefs.controlMaxSize = it
                    },
                    onHalfScreenChange = {
                        halfScreen = it
                        Prefs.controlHalfScreen = it
                    },
                    onAllowControlChange = {
                        allowControl = it
                        Prefs.controlAllowControl = it
                    },
                    onAudioEnabledChange = {
                        audioEnabled = it
                        Prefs.controlAudioEnabled = it
                        if (!it) {
                            // 关闭勾选时清掉历史警告
                            ControlSessionManager.audioWarning.value = ""
                        }
                    },
                    onConnect = { startSession() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ControlSessionPane(
                    s = s,
                    displayMode = ControlDisplayMode.HALF,
                    maxSize = activeConfig?.maxSize ?: maxSize,
                    deviceLabel = remoteName.ifBlank { deviceName.ifBlank { s.statusConnected } },
                    controlEnabled = controlEnabled,
                    onDisconnect = { scope.launch { ControlSessionManager.stop("disconnect_btn") } },
                    onNavBack = { ControlSessionManager.injectNavBack() },
                    onNavHome = { ControlSessionManager.injectNavHome() },
                    onNavRecents = { ControlSessionManager.injectNavRecents() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (running && displayMode == ControlDisplayMode.FULL_COMPAT) {
            Dialog(
                onDismissRequest = { handleExitBack() },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                ImmersiveEffect(enabled = true)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    ControlSessionPane(
                        s = s,
                        displayMode = ControlDisplayMode.FULL_COMPAT,
                        maxSize = activeConfig?.maxSize ?: maxSize,
                        deviceLabel = remoteName.ifBlank { deviceName.ifBlank { s.statusConnected } },
                        controlEnabled = controlEnabled,
                        onDisconnect = { scope.launch { ControlSessionManager.stop("disconnect_btn") } },
                        onNavBack = { ControlSessionManager.injectNavBack() },
                        onNavHome = { ControlSessionManager.injectNavHome() },
                        onNavRecents = { ControlSessionManager.injectNavRecents() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    LaunchedEffect(connected, isFastboot, running) {
        if (running && (!connected || isFastboot)) {
            ControlSessionManager.stop(if (isFastboot) "fastboot" else "adb_lost")
            statusHint = if (isFastboot) s.controlStatusFastboot else s.controlStatusDisconnected
        }
    }
}

@Composable
private fun ImmersiveEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val activity = view.context as? Activity
        val window = activity?.window
        val controller = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null

        if (enabled && window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun ControlSetupPane(
    s: Strings,
    connected: Boolean,
    isFastboot: Boolean,
    deviceName: String,
    maxSize: Int,
    halfScreen: Boolean,
    allowControl: Boolean,
    audioEnabled: Boolean,
    audioWarning: String,
    preparing: Boolean,
    canStart: Boolean,
    statusHint: String,
    onMaxSizeChange: (Int) -> Unit,
    onHalfScreenChange: (Boolean) -> Unit,
    onAllowControlChange: (Boolean) -> Unit,
    onAudioEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        isFastboot -> s.controlStatusFastboot
        connected -> s.controlStatusConnected.format(deviceName.ifBlank { s.statusConnected })
        else -> s.controlStatusDisconnected
    }
    val statusColor = when {
        isFastboot -> MaterialTheme.colorScheme.error
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = s.controlTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (!connected || isFastboot) {
                        Text(
                            text = if (isFastboot) s.controlStatusFastboot else s.controlGoHomeConnect,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (statusHint.isNotBlank()) {
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = s.controlQualityLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(480, 720, 1080).forEach { tier ->
                            FilterChip(
                                selected = maxSize == tier,
                                onClick = { if (!preparing) onMaxSizeChange(tier) },
                                enabled = !preparing,
                                label = { Text("${tier}p") }
                            )
                        }
                    }
                    Text(
                        text = s.controlQualityHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = halfScreen,
                        onCheckedChange = { if (!preparing) onHalfScreenChange(it) },
                        enabled = !preparing
                    )
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = s.controlHalfScreenLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = s.controlHalfScreenHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allowControl,
                        onCheckedChange = { if (!preparing) onAllowControlChange(it) },
                        enabled = !preparing
                    )
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = s.controlAllowControlLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = s.controlAllowControlHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = audioEnabled,
                            onCheckedChange = { if (!preparing) onAudioEnabledChange(it) },
                            enabled = !preparing
                        )
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                text = s.controlAudioLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = s.controlAudioHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (audioWarning.isNotBlank()) {
                        Text(
                            text = audioWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = s.controlPlaceholderReady,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onConnect,
            enabled = canStart && !preparing,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            // 按钮内容区用 Row+spacedBy 排布，避免 Spacer 命名参数在 CI 上编译翻车
            if (preparing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(s.controlConnecting)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(s.controlConnectBtn)
                }
            }
        }
    }
}

@Composable
private fun ControlSessionPane(
    s: Strings,
    displayMode: ControlDisplayMode,
    maxSize: Int,
    deviceLabel: String,
    controlEnabled: Boolean,
    onDisconnect: () -> Unit,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (displayMode) {
            ControlDisplayMode.HALF -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.controlTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$deviceLabel · ${maxSize}p · " +
                                        if (controlEnabled) s.controlAllowControlLabel else s.controlReadonlyBadge,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = onDisconnect) {
                                Text(s.controlDisconnectBtn)
                            }
                        }
                    }

                    RemoteVideoSurface(
                        s = s,
                        controlEnabled = controlEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.58f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.42f)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!controlEnabled) {
                            Text(
                                text = s.controlSessionStubHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = s.controlPlaceholderRunning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            ControlDisplayMode.FULL_COMPAT -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    RemoteVideoSurface(
                        s = s,
                        controlEnabled = controlEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    ) {
                        Text(s.controlDisconnectBtn)
                    }
                }
            }
        }

        if (controlEnabled) {
            FloatingNavCluster(
                s = s,
                onBack = onNavBack,
                onHome = onNavHome,
                onRecents = onNavRecents,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RemoteVideoSurface(
    s: Strings,
    controlEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val videoW by ControlSessionManager.videoWidth
    val videoH by ControlSessionManager.videoHeight
    var hasSurface by remember { mutableStateOf(false) }
    val currentControlEnabled = androidx.compose.runtime.rememberUpdatedState(controlEnabled)
    val touchHandler = remember {
        com.aoooa.adb.control.RemoteTouchHandler { action, id, x, y, width, height ->
            ControlSessionManager.injectTouch(action, x, y, id, width, height)
        }
    }
    DisposableEffect(touchHandler) {
        onDispose { touchHandler.releaseAll() }
    }
    LaunchedEffect(controlEnabled) {
        if (!controlEnabled) touchHandler.releaseAll()
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0B0F19))
            .border(1.dp, Color(0xFF1E293B))
    ) {
        AndroidView(
            factory = { ctx ->
                object : SurfaceView(ctx) {
                    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                        super.onWindowFocusChanged(hasWindowFocus)
                        if (!hasWindowFocus) touchHandler.releaseAll()
                    }
                }.apply {
                    setZOrderMediaOverlay(true)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            hasSurface = true
                            ControlSessionManager.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            ControlSessionManager.attachSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            touchHandler.releaseAll()
                            hasSurface = false
                            ControlSessionManager.attachSurface(null)
                        }
                    })
                    setOnTouchListener { v, event ->
                        if (!currentControlEnabled.value || !ControlSessionManager.allowControlNow) {
                            touchHandler.releaseAll()
                            return@setOnTouchListener false
                        }
                        touchHandler.updateGeometry(
                            com.aoooa.adb.control.RemoteTouchHandler.Geometry(
                                v.width, v.height,
                                ControlSessionManager.videoWidth.intValue,
                                ControlSessionManager.videoHeight.intValue
                            )
                        )
                        val contacts = (0 until event.pointerCount).map { index ->
                            com.aoooa.adb.control.RemoteTouchHandler.Contact(
                                event.getPointerId(index), event.getX(index), event.getY(index)
                            )
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchHandler.releaseAll()
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                touchHandler.down(contacts[event.actionIndex])
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                touchHandler.move(contacts)
                                touchHandler.down(contacts[event.actionIndex])
                            }
                            MotionEvent.ACTION_MOVE -> touchHandler.move(contacts)
                            MotionEvent.ACTION_POINTER_UP -> {
                                touchHandler.move(contacts)
                                touchHandler.up(event.getPointerId(event.actionIndex))
                            }
                            MotionEvent.ACTION_UP -> {
                                touchHandler.move(contacts)
                                touchHandler.releaseAll()
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                touchHandler.releaseAll()
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            else -> return@setOnTouchListener false
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                touchHandler.updateGeometry(
                    com.aoooa.adb.control.RemoteTouchHandler.Geometry(
                        view.width, view.height, videoW, videoH
                    )
                )
            }
        )

        if (!hasSurface || videoW <= 0) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = s.controlPlaceholderRunning,
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FloatingNavCluster(
    s: Strings,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val clusterWidthPx = with(density) { 56.dp.toPx() }
        val clusterHeightPx = with(density) { 280.dp.toPx() }
        val maxX = (constraints.maxWidth - clusterWidthPx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - clusterHeightPx).coerceAtLeast(0f)

        var offsetX by remember { mutableFloatStateOf(maxX) }
        var offsetY by remember { mutableFloatStateOf(maxY * 0.42f) }

        LaunchedEffect(maxX, maxY) {
            offsetX = offsetX.coerceIn(0f, maxX)
            offsetY = offsetY.coerceIn(0f, maxY)
        }

        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        offsetX.coerceIn(0f, maxX).roundToInt(),
                        offsetY.coerceIn(0f, maxY).roundToInt()
                    )
                }
                .width(56.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                        },
                        onDragEnd = {
                            offsetX = if (offsetX + clusterWidthPx / 2f < maxX / 2f) 0f else maxX
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FloatingNavButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = s.controlNavBack,
                onClick = onBack
            )
            FloatingNavButton(
                icon = Icons.Filled.Home,
                label = s.controlNavHome,
                onClick = onHome
            )
            FloatingNavButton(
                icon = Icons.Filled.RadioButtonUnchecked,
                label = s.controlNavRecents,
                onClick = onRecents
            )
            FloatingNavButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = s.control.volumeUp,
                onClick = { ControlSessionManager.injectKey(KeyEvent.KEYCODE_VOLUME_UP) }
            )
            FloatingNavButton(
                icon = Icons.AutoMirrored.Filled.VolumeDown,
                label = s.control.volumeDown,
                onClick = { ControlSessionManager.injectKey(KeyEvent.KEYCODE_VOLUME_DOWN) }
            )
        }
    }
}

@Composable
private fun FloatingNavButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = Color(0xE6101829),
        shadowElevation = 4.dp,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
