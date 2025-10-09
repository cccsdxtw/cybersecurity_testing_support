package com.example.overlaytester

import android.content.Intent
import android.graphics.Color
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
                    onShowFull = { showOverlay(fullscreen = true) },
                    onShowPartial = { showOverlay(fullscreen = false) },
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

    private fun showOverlay(fullscreen: Boolean) {
        removeOverlay()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先開啟 overlay 權限", Toast.LENGTH_SHORT).show()
            return
        }

        val root = FrameLayout(this)
        // 半透明灰底
        root.setBackgroundColor(Color.argb(120, 0, 0, 0))

        // 左上角提示文字
        val overlayLabel = android.widget.TextView(this).apply {
            text = if (fullscreen) "遮蔽(全畫面)" else "遮蔽(部分畫面)"
            setBackgroundColor(Color.argb(220, 255, 60, 60)) // 紅底
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            textSize = 16f
        }
        val lpLabel = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = 50
            leftMargin = 30
        }
        root.addView(overlayLabel, lpLabel)

        // Overlay LayoutParams
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else 400,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

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
    onShowFull: () -> Unit,
    onShowPartial: () -> Unit,
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
            Button(onClick = onRequestPermission) {
                Text("開啟權限(請同意Overlaytester覆蓋的權限)")
            }
            Button(onClick = onShowFull) {
                Text("開啟覆蓋(全畫面)")
            }
            Button(onClick = onShowPartial) {
                Text("開啟覆蓋(部分畫面)")
            }
            Button(onClick = onRemoveOverlay) {
                Text("關閉覆蓋")
            }
            Button(onClick = {
                Toast.makeText(
                    context,
                    "先開啟權限再覆蓋後，再去原本 App 測試遮蔽偵測",
                    Toast.LENGTH_LONG
                ).show()
            }) {
                Text("測試說明")
            }
        }
    }
}
