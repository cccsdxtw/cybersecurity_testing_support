package com.example.overlaytester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 螢幕擷取 session：授權一次後可重複延遲截圖／錄影，直到「結束螢幕擷取」。
 * [isSessionActive] 與 UI「螢幕擷取權限」狀態一致。
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var countdownRunnable: Runnable? = null
    private var isRecording = false
    private var activeToast: Toast? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        workerThread = HandlerThread("capture-worker").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.resultDataExtra()
                if (resultData == null) {
                    toast("缺少螢幕擷取授權")
                    endSessionAndStop()
                    return START_NOT_STICKY
                }
                startAsForeground("螢幕擷取 session 進行中")
                if (!openSession(resultCode, resultData)) {
                    toast("無法建立擷取 session")
                    endSessionAndStop()
                } else {
                    toast("擷取 session 已開始，可重複截圖／錄影")
                    updateNotification("擷取 session 進行中（可重複截／錄）")
                }
            }
            ACTION_DELAYED_SCREENSHOT, ACTION_DELAYED_RECORD -> {
                if (!isSessionActive || mediaProjection == null) {
                    toast("請先開啟螢幕擷取權限（建立 session）")
                    return START_NOT_STICKY
                }
                if (isRecording) {
                    toast("請先停止錄影再操作")
                    return START_NOT_STICKY
                }
                val delaySec = intent.getIntExtra(EXTRA_DELAY_SEC, 0)
                if (delaySec > 0) {
                    startCountdown(delaySec, intent.action!!)
                } else {
                    runCaptureNow(intent.action!!)
                }
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingAndSave(keepSession = true)
            }
            ACTION_END_SESSION, ACTION_CANCEL -> {
                endSessionAndStop()
            }
        }
        return START_STICKY
    }

    private fun Intent.resultDataExtra(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startAsForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun openSession(resultCode: Int, resultData: Intent): Boolean {
        cleanupDisplay()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData) ?: return false
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    // 使用者從系統關掉擷取，或系統回收 → session 結束，UI 會變未取得
                    toast("螢幕擷取已被系統結束")
                    endSessionAndStop()
                }
            }
        }, mainHandler)
        mediaProjection = projection
        setSessionActive(true)
        return true
    }

    private fun startCountdown(delaySec: Int, action: String) {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        val actionName = if (action == ACTION_DELAYED_RECORD) "錄影" else "截圖"
        toast("${delaySec} 秒後${actionName}，請切到要測的 App")
        var left = delaySec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    updateNotification("倒數 $left 秒…請切到要測的 App")
                    left--
                    countdownRunnable = this
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    countdownRunnable = null
                    runCaptureNow(action)
                }
            }
        }
        countdownRunnable = tick
        mainHandler.post(tick)
    }

    private fun runCaptureNow(action: String) {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        when (action) {
            ACTION_DELAYED_SCREENSHOT -> {
                updateNotification("正在截圖…")
                toast("開始截圖")
                // 稍等讓浮窗有時間從畫面上移除
                workerHandler?.postDelayed({ captureScreenshot() }, 250L)
            }
            ACTION_DELAYED_RECORD -> {
                updateNotification("錄影中…可按停止錄影")
                notifyCaptureEvent(EVENT_RECORD_STARTED)
                workerHandler?.post { startRecording() }
            }
        }
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            Triple(b.width(), b.height(), density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun captureScreenshot() {
        try {
            val projection = mediaProjection
            if (projection == null) {
                toast("擷取 session 已失效，請重新授權")
                notifyCaptureEvent(EVENT_SCREENSHOT_DONE)
                endSessionAndStop()
                return
            }
            val (width, height, density) = screenMetrics()
            val w = width and 1.inv()
            val h = height and 1.inv()

            cleanupDisplay()
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "cts-shot",
                w,
                h,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )

            Thread.sleep(400)
            var image = imageReader?.acquireLatestImage()
            if (image == null) {
                Thread.sleep(300)
                image = imageReader?.acquireLatestImage()
            }
            if (image == null) {
                cleanupDisplay()
                mainHandler.post {
                    toast("截圖失敗：沒拿到畫面（可能被 FLAG_SECURE 擋）")
                    updateNotification("擷取 session 進行中（可重複截／錄）")
                    notifyCaptureEvent(EVENT_SCREENSHOT_DONE)
                }
                return
            }

            val bitmap = imageToBitmap(image)
            image.close()
            val uriOrPath = saveBitmap(bitmap)
            bitmap.recycle()
            cleanupDisplay()

            mainHandler.post {
                toast(
                    if (uriOrPath != null) "截圖已存（session 仍有效，可再截）"
                    else "截圖儲存失敗"
                )
                updateNotification("擷取 session 進行中（可重複截／錄）")
                notifyCaptureEvent(EVENT_SCREENSHOT_DONE)
            }
        } catch (e: Exception) {
            cleanupDisplay()
            mainHandler.post {
                toast("截圖失敗: ${e.message}")
                updateNotification("擷取 session 進行中（可重複截／錄）")
                notifyCaptureEvent(EVENT_SCREENSHOT_DONE)
            }
        }
    }

    private fun startRecording() {
        try {
            if (isRecording) return
            val projection = mediaProjection
            if (projection == null) {
                toast("擷取 session 已失效，請重新授權")
                endSessionAndStop()
                return
            }
            val (width, height, density) = screenMetrics()
            var w = width and 1.inv()
            var h = height and 1.inv()
            if (w > 1080) {
                val scale = 1080f / w
                w = 1080
                h = (h * scale).toInt() and 1.inv()
            }

            cleanupDisplay()
            val outFile = createMovieFile()
            recordingFile = outFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoEncodingBitRate(6_000_000)
                setVideoFrameRate(30)
                prepare()
            }

            virtualDisplay = projection.createVirtualDisplay(
                "cts-rec",
                w,
                h,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                workerHandler
            )
            recorder.start()
            isRecording = true
            mainHandler.post {
                updateNotification("錄影中…按「停止錄影」結束這段")
                toast("開始錄影")
            }
        } catch (e: Exception) {
            cleanupDisplay()
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            isRecording = false
            mainHandler.post {
                toast("錄影開始失敗: ${e.message}")
                updateNotification("擷取 session 進行中（可重複截／錄）")
            }
        }
    }

    private fun stopRecordingAndSave(keepSession: Boolean) {
        if (!isRecording) {
            toast("目前沒有在錄影")
            return
        }
        workerHandler?.post {
            try {
                mediaRecorder?.apply {
                    try {
                        stop()
                    } catch (_: Exception) {
                    }
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false
                cleanupDisplay()

                val file = recordingFile
                val saved = if (file != null && file.exists()) {
                    publishVideoToGallery(file)
                } else null

                mainHandler.post {
                    toast(
                        if (saved != null) {
                            if (keepSession) "錄影已存（session 仍有效，可再錄）"
                            else "錄影已存：$saved"
                        } else {
                            "錄影儲存失敗（若畫面全黑，可能被 FLAG_SECURE 擋）"
                        }
                    )
                    if (keepSession && isSessionActive) {
                        updateNotification("擷取 session 進行中（可重複截／錄）")
                    }
                    notifyCaptureEvent(EVENT_RECORD_STOPPED)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    toast("停止錄影失敗: ${e.message}")
                    notifyCaptureEvent(EVENT_RECORD_STOPPED)
                }
            }
        }
    }

    private fun notifyCaptureEvent(event: String) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_EVENT).setPackage(packageName)
                .putExtra(EXTRA_EVENT, event)
        )
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun saveBitmap(bitmap: Bitmap): String? {
        val name = "CTS_${timestamp()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/CybersecurityTestingSupport"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "CybersecurityTestingSupport"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }
    }

    private fun createMovieFile(): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CybersecurityTestingSupport")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "CTS_${timestamp()}.mp4")
    }

    private fun publishVideoToGallery(file: File): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/CybersecurityTestingSupport"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return file.absolutePath
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri.toString()
        } else {
            file.absolutePath
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun cleanupDisplay() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
    }

    private fun cleanupAll() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {
            }
            isRecording = false
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        cleanupDisplay()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun endSessionAndStop() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        cleanupAll()
        setSessionActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截圖與錄影",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopRec = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endSession = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java).apply { action = ACTION_END_SESSION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(0, "停止錄影", stopRec)
            .addAction(0, "結束擷取", endSession)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun toast(msg: String) {
        mainHandler.post {
            activeToast?.cancel()
            activeToast = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).also {
                it.show()
            }
        }
    }

    override fun onDestroy() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupAll()
        setSessionActive(false)
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SESSION = "com.example.overlaytester.START_SESSION"
        const val ACTION_DELAYED_SCREENSHOT = "com.example.overlaytester.DELAYED_SCREENSHOT"
        const val ACTION_DELAYED_RECORD = "com.example.overlaytester.DELAYED_RECORD"
        const val ACTION_STOP_RECORDING = "com.example.overlaytester.STOP_RECORDING"
        const val ACTION_END_SESSION = "com.example.overlaytester.END_SESSION"
        const val ACTION_CANCEL = "com.example.overlaytester.CANCEL"

        const val ACTION_SESSION_CHANGED = "com.example.overlaytester.SESSION_CHANGED"
        const val EXTRA_SESSION_ACTIVE = "session_active"

        const val ACTION_CAPTURE_EVENT = "com.example.overlaytester.CAPTURE_EVENT"
        const val EXTRA_EVENT = "event"
        const val EVENT_SCREENSHOT_DONE = "screenshot_done"
        const val EVENT_RECORD_STARTED = "record_started"
        const val EVENT_RECORD_STOPPED = "record_stopped"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DELAY_SEC = "delay_sec"
        const val DEFAULT_DELAY_SEC = 5

        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var isSessionActive: Boolean = false
            private set

        private fun CaptureService.setSessionActive(active: Boolean) {
            isSessionActive = active
            sendBroadcast(
                Intent(ACTION_SESSION_CHANGED).setPackage(packageName)
                    .putExtra(EXTRA_SESSION_ACTIVE, active)
            )
        }

        fun startSession(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startScreenshot(context: Context, delaySec: Int = 0) {
            context.startService(
                Intent(context, CaptureService::class.java).apply {
                    action = ACTION_DELAYED_SCREENSHOT
                    putExtra(EXTRA_DELAY_SEC, delaySec)
                }
            )
        }

        fun startRecord(context: Context, delaySec: Int = 0) {
            context.startService(
                Intent(context, CaptureService::class.java).apply {
                    action = ACTION_DELAYED_RECORD
                    putExtra(EXTRA_DELAY_SEC, delaySec)
                }
            )
        }

        fun stopRecording(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).apply {
                    action = ACTION_STOP_RECORDING
                }
            )
        }

        fun endSession(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).apply {
                    action = ACTION_END_SESSION
                }
            )
        }
    }
}
