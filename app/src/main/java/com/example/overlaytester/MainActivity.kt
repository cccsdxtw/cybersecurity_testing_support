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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.example.overlaytester.ui.theme.OverlaytesterTheme

class MainActivity : ComponentActivity() {

    /** 遮罩本體；穿透模式下另有可點擊的標籤視窗 */
    private var overlayMaskView: FrameLayout? = null
    private var overlayLabelView: View? = null
    private var controlPanelView: View? = null
    private var controlPanelLp: WindowManager.LayoutParams? = null
    /** true=展開面板，false=僅圖標；null=完全隱藏（截圖中） */
    private var controlPanelExpanded: Boolean? = false
    /** 貼邊側：展開／收合都跟這側，避免右邊收合跑到左邊 */
    private var controlDockRight: Boolean = true
    private var windowManager: WindowManager? = null
    private var onControlPanelVisibilityChanged: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            OverlaytesterTheme(darkTheme = true) {
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
                var controlPanelOpen by remember {
                    mutableStateOf(controlPanelView != null)
                }

                DisposableEffect(Unit) {
                    onControlPanelVisibilityChanged = { open -> controlPanelOpen = open }
                    onDispose { onControlPanelVisibilityChanged = null }
                }

                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            when (intent?.action) {
                                CaptureService.ACTION_SESSION_CHANGED -> {
                                    hasCaptureSession = intent.getBooleanExtra(
                                        CaptureService.EXTRA_SESSION_ACTIVE,
                                        false
                                    ) || CaptureService.isSessionActive
                                }
                                CaptureService.ACTION_CAPTURE_EVENT -> {
                                    when (intent.getStringExtra(CaptureService.EXTRA_EVENT)) {
                                        CaptureService.EVENT_SCREENSHOT_DONE -> {
                                            // 截圖結束：浮窗以圖標回來
                                            showControlFab()
                                            controlPanelOpen = true
                                        }
                                        CaptureService.EVENT_RECORD_STARTED,
                                        CaptureService.EVENT_RECORD_STOPPED -> {
                                            // 錄影中／結束都維持圖標
                                            showControlFab()
                                            controlPanelOpen = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(CaptureService.ACTION_SESSION_CHANGED)
                        addAction(CaptureService.ACTION_CAPTURE_EVENT)
                    }
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
                    controlPanelOpen = controlPanelOpen,
                    onToggleControlPanel = {
                        if (controlPanelView != null || controlPanelExpanded != null) {
                            hideControlPanel()
                            controlPanelOpen = false
                        } else {
                            if (showControlFab()) {
                                controlPanelOpen = true
                            }
                        }
                    },
                    onRequestOverlayPermission = {
                        requestOverlayPermission()
                    },
                    onRefreshPermissions = {
                        hasOverlayPermission = hasOverlayPermission()
                        hasNotificationPermission = hasNotificationPermission()
                        hasCaptureSession = CaptureService.isSessionActive
                        controlPanelOpen = controlPanelView != null
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
                    onScreenshot = {
                        if (!ensureCaptureSession()) return@OverlayTesterUI
                        if (controlPanelView != null) hideControlPanelTemporarily()
                        // 主畫面：5 秒倒數
                        CaptureService.startScreenshot(
                            this@MainActivity,
                            CaptureService.DEFAULT_DELAY_SEC
                        )
                        moveTaskToBack(true)
                    },
                    onStartRecord = {
                        if (!ensureCaptureSession()) return@OverlayTesterUI
                        if (controlPanelView != null) showControlFab()
                        // 主畫面：5 秒倒數
                        CaptureService.startRecord(
                            this@MainActivity,
                            CaptureService.DEFAULT_DELAY_SEC
                        )
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

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
        } else {
            toast("已取得 Overlay 權限")
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun rememberPanelPosition(): Pair<Int, Int> {
        val lp = controlPanelLp
        return if (lp != null) lp.x to lp.y else dp(16) to dp(120)
    }

    private fun detachControlView() {
        controlPanelView?.let {
            try {
                windowManager?.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        controlPanelView = null
    }

    /** 只顯示可拖曳圖標；點一下展開功能面板 */
    private fun showControlFab(): Boolean {
        if (!hasOverlayPermission()) {
            toast("請先開啟 Overlay 權限（浮窗需要）")
            return false
        }
        val (_, posY) = rememberPanelPosition()
        detachControlView()

        val accent = Color.rgb(79, 127, 122)
        val size = dp(56)
        val fab = TextView(this).apply {
            text = "測"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(accent)
            elevation = dp(8).toFloat()
            setOnClickListener { showControlPanelExpanded() }
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = posY
        }
        applyDockEdge(lp, size, size)
        attachDraggable(fab, lp, fab, clickToActivate = true)

        return try {
            windowManager?.addView(fab, lp)
            controlPanelView = fab
            controlPanelLp = lp
            controlPanelExpanded = false
            onControlPanelVisibilityChanged?.invoke(true)
            true
        } catch (e: Exception) {
            toast("開啟浮窗失敗: ${e.message}")
            false
        }
    }

    /** 展開功能列表：標題列專責拖曳，下方 ScrollView 只負責滑動 */
    private fun showControlPanelExpanded(): Boolean {
        if (!hasOverlayPermission()) {
            toast("請先開啟 Overlay 權限（浮窗需要）")
            return false
        }
        val (_, posY) = rememberPanelPosition()
        detachControlView()

        val panelBg = Color.rgb(30, 30, 30)
        val accent = Color.rgb(79, 127, 122)
        val textColor = Color.rgb(232, 232, 232)
        val panelW = dp(260)
        val panelH = dp(420)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(8).toFloat()
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 只有這塊文字可拖；右側短鈕不綁拖曳，避免和滑動打架
        val dragHandle = TextView(this).apply {
            setText(R.string.float_panel_drag_handle)
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(textColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.05f)
            setPadding(0, dp(4), dp(6), dp(4))
        }
        titleBar.addView(
            dragHandle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleBar.addView(
            panelButton("收", Color.rgb(90, 90, 90)) { showControlFab() },
            LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        )
        titleBar.addView(
            panelButton("Ｘ", Color.rgb(120, 60, 60)) { hideControlPanel() },
            LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            titleBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addSection(title: String) {
            list.addView(
                TextView(this).apply {
                    text = title
                    setTextColor(accent)
                    textSize = 13f
                    setPadding(0, dp(6), 0, dp(4))
                }
            )
        }

        fun addAction(label: String, action: () -> Unit) {
            list.addView(
                panelButton(label, accent, action),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
        }

        addSection("遮罩")
        addAction("遮罩-不透(全畫面)") { showOverlay(fullscreen = true, touchThrough = false) }
        addAction("遮罩-不透(部分)") { showOverlay(fullscreen = false, touchThrough = false) }
        addAction("遮罩-穿透(全畫面)") { showOverlay(fullscreen = true, touchThrough = true) }
        addAction("遮罩-穿透(部分)") { showOverlay(fullscreen = false, touchThrough = true) }
        addAction("關閉覆蓋") { removeOverlay() }

        addSection("截圖 / 錄影")
        // 浮窗內：立即截／錄（無倒數）
        addAction("截圖") {
            if (!ensureCaptureSession()) return@addAction
            hideControlPanelTemporarily()
            CaptureService.startScreenshot(this, delaySec = 0)
        }
        addAction("開始錄影") {
            if (!ensureCaptureSession()) return@addAction
            showControlFab()
            CaptureService.startRecord(this, delaySec = 0)
        }
        addAction("停止錄影") { CaptureService.stopRecording(this) }
        addAction("結束螢幕擷取") {
            CaptureService.endSession(this)
            toast("已結束螢幕擷取 session")
        }

        val scroll = ScrollView(this).apply {
            isNestedScrollingEnabled = true
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val lp = WindowManager.LayoutParams(
            panelW,
            panelH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = posY
        }
        applyDockEdge(lp, panelW, panelH)
        // 只綁在「功能（拖曳移動）」文字上
        attachDraggable(dragHandle, lp, root, clickToActivate = false)

        return try {
            windowManager?.addView(root, lp)
            controlPanelView = root
            controlPanelLp = lp
            controlPanelExpanded = true
            onControlPanelVisibilityChanged?.invoke(true)
            true
        } catch (e: Exception) {
            toast("展開浮窗失敗: ${e.message}")
            false
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** 依記住的左右側貼邊（展開／收合寬度不同也不會跑邊） */
    private fun applyDockEdge(
        lp: WindowManager.LayoutParams,
        viewW: Int,
        viewH: Int
    ) {
        val (screenW, screenH) = screenSize()
        val w = viewW.coerceAtLeast(1)
        val h = viewH.coerceAtLeast(1)
        lp.x = if (controlDockRight) (screenW - w).coerceAtLeast(0) else 0
        lp.y = lp.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, view: View) {
        val (screenW, _) = screenSize()
        val viewW = if (view.width > 0) view.width else if (lp.width > 0) lp.width else dp(56)
        val viewH = if (view.height > 0) view.height else if (lp.height > 0) lp.height else dp(56)
        val centerX = lp.x + viewW / 2
        controlDockRight = centerX >= screenW / 2
        applyDockEdge(lp, viewW, viewH)
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    /**
     * @param clickToActivate 圖標模式：沒拖就點擊展開；標題拖曳列則為 false
     */
    private fun attachDraggable(
        handle: View,
        lp: WindowManager.LayoutParams,
        targetView: View? = null,
        clickToActivate: Boolean = true
    ) {
        var dragDx = 0f
        var dragDy = 0f
        var moved = false
        val touchSlop = dp(6)
        handle.setOnTouchListener { v, event ->
            val movingView = targetView ?: v
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDx = event.rawX - lp.x
                    dragDy = event.rawY - lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (event.rawX - dragDx).toInt()
                    val ny = (event.rawY - dragDy).toInt()
                    if (kotlin.math.abs(nx - lp.x) > touchSlop ||
                        kotlin.math.abs(ny - lp.y) > touchSlop
                    ) {
                        moved = true
                    }
                    val (screenW, screenH) = screenSize()
                    val viewW = if (movingView.width > 0) movingView.width else lp.width
                    val viewH = if (movingView.height > 0) movingView.height else lp.height
                    lp.x = nx.coerceIn(0, (screenW - viewW).coerceAtLeast(0))
                    lp.y = ny.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
                    try {
                        windowManager?.updateViewLayout(movingView, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        snapToEdge(lp, movingView)
                    } else if (clickToActivate) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun panelButton(text: String, bg: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setBackgroundColor(bg)
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onClick() }
        }
    }

    /** 截圖用：暫時移除浮窗，保留座標，拍完再 showControlFab */
    private fun hideControlPanelTemporarily() {
        rememberPanelPosition().let { (x, y) ->
            if (controlPanelLp == null) {
                controlPanelLp = WindowManager.LayoutParams().apply {
                    this.x = x
                    this.y = y
                }
            } else {
                controlPanelLp?.x = x
                controlPanelLp?.y = y
            }
        }
        detachControlView()
        controlPanelExpanded = null
    }

    private fun hideControlPanel() {
        detachControlView()
        controlPanelLp = null
        controlPanelExpanded = null
        onControlPanelVisibilityChanged?.invoke(false)
    }

    private fun showOverlay(fullscreen: Boolean, touchThrough: Boolean) {
        removeOverlay()
        if (!hasOverlayPermission()) {
            toast("請先開啟 Overlay 權限")
            return
        }

        // 標籤位置統一對齊「不透（部分）」：部分遮罩頂 y=200 + 內距 50、左 30
        val partialMaskY = 200
        val partialMaskHeight = 400
        val labelX = 30
        val labelY = partialMaskY + 50

        val modeLabel = buildString {
            append(if (touchThrough) "遮罩-穿透" else "遮罩-不透")
            append(if (fullscreen) "(全畫面)" else "(部分)")
            append(" · 點我關閉")
        }

        val overlayType = overlayType()

        val maskAlpha = if (touchThrough) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val inputManager = getSystemService(INPUT_SERVICE) as InputManager
                minOf(0.8f, inputManager.maximumObscuringOpacityForTouch)
            } else {
                0.8f
            }
        } else {
            0.85f
        }

        val mask = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(0, 0, 0))
            isClickable = !touchThrough
            isFocusable = false
        }

        val maskFlags = if (touchThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val maskLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else partialMaskHeight,
            overlayType,
            maskFlags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            alpha = maskAlpha
            if (!fullscreen) {
                gravity = Gravity.TOP
                y = partialMaskY
            }
        }

        // 四種模式都用獨立可點標籤，座標固定與「不透（部分）」相同
        val closeLabel = TextView(this).apply {
            text = modeLabel
            setBackgroundColor(
                if (touchThrough) Color.rgb(30, 100, 220) else Color.rgb(255, 60, 60)
            )
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            textSize = 16f
            isClickable = true
            isFocusable = false
            setOnClickListener { removeOverlay() }
        }
        val labelLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = labelX
            y = labelY
            alpha = 0.99f
        }

        try {
            windowManager?.addView(mask, maskLp)
            overlayMaskView = mask
            windowManager?.addView(closeLabel, labelLp)
            overlayLabelView = closeLabel
        } catch (e: Exception) {
            removeOverlay()
            toast("建立 overlay 失敗: ${e.message}")
        }
    }

    private fun removeOverlay() {
        listOf(overlayLabelView, overlayMaskView).forEach { view ->
            view?.let {
                try {
                    windowManager?.removeViewImmediate(it)
                } catch (_: Exception) {
                }
            }
        }
        overlayLabelView = null
        overlayMaskView = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        hideControlPanel()
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
    controlPanelOpen: Boolean,
    onToggleControlPanel: () -> Unit,
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
    onScreenshot: () -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    var helpDialog by remember { mutableStateOf<HelpDialog?>(null) }

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
            FullWidthButton(
                if (controlPanelOpen) "關閉功能浮窗" else "開啟功能浮窗"
            ) {
                onToggleControlPanel()
            }
            Text(
                text = "開啟後為「測」圖標（鬆手自動貼邊），點一下展開；展開後只拖「功能（拖曳移動）」移動，下方列表可滑動。截圖時浮窗暫隱，錄影收成圖標。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SectionTitle("一、遮罩測試")
            PermissionStatus("Overlay 權限", hasOverlayPermission)
            FullWidthButton("開啟 Overlay 權限", onRequestOverlayPermission)
            FullWidthButton("遮罩-不透(全畫面)", onShowBlockingFull)
            FullWidthButton("遮罩-不透(部分)", onShowBlockingPartial)
            FullWidthButton("遮罩-穿透(全畫面)", onShowPassThroughFull)
            FullWidthButton("遮罩-穿透(部分)", onShowPassThroughPartial)
            FullWidthButton("關閉覆蓋", onRemoveOverlay)
            FullWidthButton("遮罩測試說明") {
                helpDialog = HelpDialog(
                    title = "遮罩測試說明",
                    body = """
不透＝攔截點擊（紅標）
穿透＝點擊可透到下方 App（藍標；Android 12+ 需 window alpha≤0.8）

用途：測對方 App 的遮蔽／遮罩偵測。
可在本頁或「功能浮窗」操作；點標籤或「關閉覆蓋」可關。
                    """.trimIndent()
                )
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
                    text = "目前 session 有效：可在本頁或浮窗重複截圖／錄影。測完按「結束螢幕擷取」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            FullWidthButton("開啟通知權限", onRequestNotificationPermission)
            FullWidthButton("開啟螢幕擷取權限", onRequestCapturePermission)
            FullWidthButton("延遲截圖 (5秒)", onScreenshot)
            FullWidthButton("延遲錄影開始 (5秒)", onStartRecord)
            FullWidthButton("停止錄影", onStopRecord)
            FullWidthButton("結束螢幕擷取", onEndCaptureSession)
            FullWidthButton("截圖錄影說明") {
                helpDialog = HelpDialog(
                    title = "截圖／錄影說明",
                    body = """
1. 先開「通知權限」（前景服務需要）
2. 再開「螢幕擷取權限」建立這次測試 session
3. 本頁「延遲截圖／錄影」會倒數 5 秒；浮窗內為立即執行
4. 若有開浮窗：截圖時浮窗暫隱；錄影時收成圖標
5. 錄影中按「停止錄影」存檔；測完按「結束螢幕擷取」

若對方有 FLAG_SECURE，結果常為黑畫面。
檔案在 Pictures/Movies/OverlayTester。
                    """.trimIndent()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    helpDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { helpDialog = null },
            title = { Text(dialog.title) },
            text = {
                Text(
                    text = dialog.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { helpDialog = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

private data class HelpDialog(val title: String, val body: String)

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
