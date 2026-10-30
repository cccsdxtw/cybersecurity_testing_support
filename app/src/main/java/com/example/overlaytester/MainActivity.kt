package com.example.overlaytester

import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var overlayView: FrameLayout? = null
    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setContent {
            MaterialTheme {
                OverlayTesterUI(
                    onRequestPermission = { requestOverlayPermission() },
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
                    onRemoveOverlay = { removeOverlay() }
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "已取得 overlay 權限", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "此 Android 版本無需額外權限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOverlay(fullscreen: Boolean, touchThrough: Boolean) {
        removeOverlay()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先開啟 overlay 權限", Toast.LENGTH_SHORT).show()
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
            // 不透=紅底、穿透=藍底，方便一眼分辨
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
            // 點擊穿透到下方 App
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            // 攔截遮罩範圍內點擊；部分模式下範圍外仍可點下方
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
            // 穿透模式：Android 12+ 必須 window alpha ≤ 系統門檻，否則點擊仍被擋
            // 不透模式：可較不透明，遮蔽感較強
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
            Toast.makeText(this, "建立 overlay 失敗: ${e.message}", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayTesterUI(
    onRequestPermission: () -> Unit,
    onShowBlockingFull: () -> Unit,
    onShowBlockingPartial: () -> Unit,
    onShowPassThroughFull: () -> Unit,
    onShowPassThroughPartial: () -> Unit,
    onRemoveOverlay: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Overlay 測試工具") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("開啟權限(請同意 Overlay 覆蓋權限)")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Button(
                onClick = onShowBlockingFull,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("遮罩-不透(全畫面)")
            }
            Button(
                onClick = onShowBlockingPartial,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("遮罩-不透(部分)")
            }
            Button(
                onClick = onShowPassThroughFull,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("遮罩-穿透(全畫面)")
            }
            Button(
                onClick = onShowPassThroughPartial,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("遮罩-穿透(部分)")
            }

            Button(
                onClick = onRemoveOverlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("關閉覆蓋")
            }

            Button(
                onClick = {
                    Toast.makeText(
                        context,
                        "不透=攔截點擊(紅標)；穿透=點得過(藍標，Android12+ alpha≤0.8)。開權限後再測遮蔽偵測。",
                        Toast.LENGTH_LONG
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("測試說明")
            }
        }
    }
}
