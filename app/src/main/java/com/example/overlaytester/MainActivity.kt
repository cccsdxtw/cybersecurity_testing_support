package com.example.overlaytester

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var overlayView: FrameLayout? = null
    private var windowManager: WindowManager? = null

    /** 螢幕擷取授權結果；用完即清，避免重複使用失效的 Intent */
    private var captureResultCode: Int = Activity.RESULT_CANCELED
    private var captureResultData: Intent? = null

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
                var hasCapturePermission by remember {
                    mutableStateOf(captureResultData != null)
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
                        captureResultCode = result.resultCode
                        captureResultData = result.data
                        hasCapturePermission = true
                        toast("已取得螢幕擷取權限")
                    } else {
                        captureResultCode = Activity.RESULT_CANCELED
                        captureResultData = null
                        hasCapturePermission = false
                        toast("未授權螢幕擷取")
                    }
                }

                OverlayTesterUI(
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasCapturePermission = hasCapturePermission,
                    onRequestOverlayPermission = {
                        requestOverlayPermission()
                    },
                    onRefreshPermissions = {
                        hasOverlayPermission = hasOverlayPermission()
                        hasNotificationPermission = hasNotificationPermission()
                        hasCapturePermission = captureResultData != null
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
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        capturePermissionLauncher.launch(mgr.createScreenCaptureIntent())
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
                        if (startCaptureAction(isRecord = false)) {
                            hasCapturePermission = false
                        }
                    },
                    onDelayedRecord = {
                        if (startCaptureAction(isRecord = true)) {
                            hasCapturePermission = false
                        }
                    },
                    onStopRecord = {
                        CaptureService.stopRecording(this)
                    }
                )
            }
        }
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

    /** @return true 若已成功啟動延遲擷取 */
    private fun startCaptureAction(isRecord: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            toast("請先開啟通知權限（錄影/截圖前景服務需要）")
            return false
        }
        val data = captureResultData
        if (data == null) {
            toast("請先點「開啟螢幕擷取權限」")
            return false
        }
        // MediaProjection token 通常只能用一次，啟動後清掉，下次需重新授權
        val code = captureResultCode
        val dataCopy = Intent(data)
        captureResultData = null
        captureResultCode = Activity.RESULT_CANCELED

        if (isRecord) {
            CaptureService.startDelayedRecord(this, code, dataCopy)
            toast("5 秒後開始錄影，請切到要測的 App")
        } else {
            CaptureService.startDelayedScreenshot(this, code, dataCopy)
            toast("5 秒後截圖，請切到要測的 App")
        }
        // 方便切到目標 App
        moveTaskToBack(true)
        return true
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
    hasCapturePermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestCapturePermission: () -> Unit,
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
    val context = androidx.compose.ui.platform.LocalContext.current

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
            // ── 區塊一：遮罩 ──
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

            // ── 區塊二：截圖 / 錄影 ──
            SectionTitle("二、截圖 / 錄影測試")
            PermissionStatus("通知權限", hasNotificationPermission)
            PermissionStatus("螢幕擷取權限", hasCapturePermission)
            FullWidthButton("開啟通知權限", onRequestNotificationPermission)
            FullWidthButton("開啟螢幕擷取權限", onRequestCapturePermission)
            FullWidthButton("延遲截圖 (5秒)", onDelayedScreenshot)
            FullWidthButton("延遲錄影開始 (5秒)", onDelayedRecord)
            FullWidthButton("停止錄影", onStopRecord)
            FullWidthButton("截圖錄影說明") {
                Toast.makeText(
                    context,
                    "先開通知+螢幕擷取權限 → 延遲截圖/錄影 → 5秒內切到目標 App。若對方有 FLAG_SECURE，結果常為黑畫面。檔案在 Pictures/Movies/OverlayTester。每次擷取後需重新授權螢幕擷取。",
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
private fun PermissionStatus(label: String, granted: Boolean) {
    Text(
        text = "$label：${if (granted) "已取得" else "未取得"}",
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
