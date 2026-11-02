package com.example.overlaytester

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.input.InputManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var overlayView: FrameLayout? = null
    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setContent {
            MaterialTheme {
                var hasOverlayPermission by remember {
                    mutableStateOf(hasOverlayPermission())
                }
                var hasNotificationPermission by remember {
                    mutableStateOf(hasNotificationPermission())
                }
                // 與 CaptureService session 同步：已取得 = 這次測試 session 進行中
                var hasCaptureSession by remember {
                    mutableStateOf(CaptureService.isSessionActive)
                }

                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            hasCaptureSession = intent?.getBooleanExtra(
                                CaptureService.EXTRA_SESSION_ACTIVE,
                                false
                            ) == true || CaptureService.isSessionActive
                        }
                    }
                    val filter = IntentFilter(CaptureService.ACTION_SESSION_CHANGED)
                    ContextCompat.registerReceiver(
                        this@MainActivity,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose {
                        try {
                            unregisterReceiver(receiver)
                        } catch (_: Exception) {
                        }
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasNotificationPermission = granted
                    toast(
                        if (granted) "已取得通知權限"
                        else "未取得通知權限（前景服務通知可能被擋）"
                    )
                }

                val capturePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !hasNotificationPermission()
                        ) {
                            toast("請先開啟通知權限，再開螢幕擷取")
                            return@rememberLauncherForActivityResult
                        }
                        // 立刻建立 session；UI「已取得」= session 進行中（準）
                        CaptureService.startSession(
                            this@MainActivity,
                            result.resultCode,
                            Intent(result.data)
                        )
                        hasCaptureSession = true
                        toast("已建立擷取 session，測試期間可重複截／錄")
                    } else {
                        hasCaptureSession = CaptureService.isSessionActive
                        toast("未授權螢幕擷取")
                    }
                }

                OverlayTesterUI(
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasCaptureSession = hasCaptureSession,
                    onRequestOverlayPermission = {
                        requestOverlayPermission()
                    },
                    onRefreshPermissions = {
                        hasOverlayPermission = hasOverlayPermission()
                        hasNotificationPermission = hasNotificationPermission()
                        hasCaptureSession = CaptureService.isSessionActive
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            toast("此 Android 版本無需通知權限")
                            hasNotificationPermission = true
                        }
                    },
                    onRequestCapturePermission = {
                        if (CaptureService.isSessionActive) {
                            toast("擷取 session 已在進行中")
                            hasCaptureSession = true
                            return@OverlayTesterUI
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !hasNotificationPermission()
                        ) {
                            toast("請先開啟通知權限")
                            return@OverlayTesterUI
                        }
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        capturePermissionLauncher.launch(mgr.createScreenCaptureIntent())
                    },
                    onEndCaptureSession = {
                        CaptureService.endSession(this@MainActivity)
                        hasCaptureSession = false
                        toast("已結束螢幕擷取 session")
                    },
                    onShowBlockingFull = {
                        showOverlay(fullscreen = true, touchThrough = false)
                    },
                    onShowBlockingPartial = {
                        showOverlay(fullscreen = false, touchThrough = false)
                    },
                    onShowPassThroughFull = {
                        showOverlay(fullscreen = true, touchThrough = true)
                    },
                    onShowPassThroughPartial = {
                        showOverlay(fullscreen = false, touchThrough = true)
                    },
                    onRemoveOverlay = { removeOverlay() },
                    onDelayedScreenshot = {
                        if (!ensureCaptureSession()) return@OverlayTesterUI
                        CaptureService.startDelayedScreenshot(this@MainActivity)
                        moveTaskToBack(true)
                    },
                    onDelayedRecord = {
                        if (!ensureCaptureSession()) return@OverlayTesterUI
                        CaptureService.startDelayedRecord(this@MainActivity)
                        moveTaskToBack(true)
                    },
                    onStopRecord = {
                        CaptureService.stopRecording(this@MainActivity)
                    }
                )
            }
        }
    }

    private fun ensureCaptureSession(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            toast("請先開啟通知權限")
            return false
        }
        if (!CaptureService.isSessionActive) {
            toast("請先開啟螢幕擷取權限（建立 session）")
            return false
        }
        return true
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toast("已取得 Overlay 權限")
            }
        } else {
            toast("此 Android 版本無需額外權限")
        }
    }

    private fun showOverlay(fullscreen: Boolean, touchThrough: Boolean) {
        removeOverlay()
        if (!hasOverlayPermission()) {
            toast("請先開啟 Overlay 權限")
            return
        }

        val modeLabel = buildString {
            append(if (touchThrough) "遮罩-穿透" else "遮罩-不透")
            append(if (fullscreen) "(全畫面)" else "(部分)")
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(0, 0, 0))
            isClickable = !touchThrough
            isFocusable = false
        }

        val overlayLabel = android.widget.TextView(this).apply {
            text = modeLabel
            setBackgroundColor(
                if (touchThrough) Color.rgb(30, 100, 220) else Color.rgb(255, 60, 60)
            )
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            textSize = 16f
            isClickable = false
            isFocusable = false
        }
        root.addView(
            overlayLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = 50
                leftMargin = 30
            }
        )

        val flags = if (touchThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else 400,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            alpha = if (touchThrough) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val inputManager = getSystemService(INPUT_SERVICE) as InputManager
                    minOf(0.8f, inputManager.maximumObscuringOpacityForTouch)
                } else {
                    0.8f
                }
            } else {
                0.85f
            }
        }

        if (!fullscreen) {
            layoutParams.gravity = Gravity.TOP
            layoutParams.y = 200
        }

        overlayView = root
        try {
            windowManager?.addView(root, layoutParams)
        } catch (e: Exception) {
            toast("建立 overlay 失敗: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeViewImmediate(it)
            } catch (_: Exception) {
            }
            overlayView = null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayTesterUI(
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasCaptureSession: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestCapturePermission: () -> Unit,
    onEndCaptureSession: () -> Unit,
    onShowBlockingFull: () -> Unit,
    onShowBlockingPartial: () -> Unit,
    onShowPassThroughFull: () -> Unit,
    onShowPassThroughPartial: () -> Unit,
    onRemoveOverlay: () -> Unit,
    onDelayedScreenshot: () -> Unit,
    onDelayedRecord: () -> Unit,
    onStopRecord: () -> Unit
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current

    DisposableEffect(context) {
        val activity = context as ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("資安測試工具") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionTitle("一、遮罩測試")
            PermissionStatus("Overlay 權限", hasOverlayPermission)
            FullWidthButton("開啟 Overlay 權限", onRequestOverlayPermission)
            FullWidthButton("遮罩-不透(全畫面)", onShowBlockingFull)
            FullWidthButton("遮罩-不透(部分)", onShowBlockingPartial)
            FullWidthButton("遮罩-穿透(全畫面)", onShowPassThroughFull)
            FullWidthButton("遮罩-穿透(部分)", onShowPassThroughPartial)
            FullWidthButton("關閉覆蓋", onRemoveOverlay)
            FullWidthButton("遮罩測試說明") {
                Toast.makeText(
                    context,
                    "不透=攔截點擊(紅標)；穿透=點得過(藍標)。用來測對方 App 的遮蔽偵測。",
                    Toast.LENGTH_LONG
                ).show()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionTitle("二、截圖 / 錄影測試")
            PermissionStatus("通知權限", hasNotificationPermission)
            PermissionStatus(
                label = "螢幕擷取",
                granted = hasCaptureSession,
                grantedText = "已取得（測試 session 進行中）",
                deniedText = "未取得"
            )
            if (!hasCaptureSession) {
                Text(
                    text = "Android 不允許永久授權螢幕擷取（防背景偷錄）。結束 session、滑掉通知或重開 App 後需再授權一次；授權後同一輪測試可一直截／錄。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "目前 session 有效：可重複延遲截圖／錄影。測完請按「結束螢幕擷取」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            FullWidthButton("開啟通知權限", onRequestNotificationPermission)
            FullWidthButton("開啟螢幕擷取權限", onRequestCapturePermission)
            FullWidthButton("結束螢幕擷取", onEndCaptureSession)
            FullWidthButton("延遲截圖 (5秒)", onDelayedScreenshot)
            FullWidthButton("延遲錄影開始 (5秒)", onDelayedRecord)
            FullWidthButton("停止錄影", onStopRecord)
            FullWidthButton("截圖錄影說明") {
                Toast.makeText(
                    context,
                    "先開通知 → 開螢幕擷取（建立 session）→ 可多次延遲截圖／錄影 → 測完按結束螢幕擷取。FLAG_SECURE 常為黑畫面。",
                    Toast.LENGTH_LONG
                ).show()
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun PermissionStatus(
    label: String,
    granted: Boolean,
    grantedText: String = "已取得",
    deniedText: String = "未取得"
) {
    Text(
        text = "$label：${if (granted) grantedText else deniedText}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (granted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FullWidthButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}
