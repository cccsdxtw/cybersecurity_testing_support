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
 * 用 MediaProjection 做延遲截圖 / 錄影，方便測對方 App 的 FLAG_SECURE 等防護。
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        workerThread = HandlerThread("capture-worker").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECORDING -> {
                stopRecordingAndSave()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                cleanupAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DELAYED_SCREENSHOT, ACTION_DELAYED_RECORD -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val delaySec = intent.getIntExtra(EXTRA_DELAY_SEC, DEFAULT_DELAY_SEC)
                if (resultData == null) {
                    toast("缺少螢幕擷取授權，請先點「開啟螢幕擷取權限」")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(isRecord = intent.action == ACTION_DELAYED_RECORD)
                startCountdown(delaySec, intent.action!!, resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(isRecord: Boolean) {
        val notification = buildNotification(
            if (isRecord) "準備延遲錄影…" else "準備延遲截圖…"
        )
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

    private fun startCountdown(
        delaySec: Int,
        action: String,
        resultCode: Int,
        resultData: Intent
    ) {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        var left = delaySec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    updateNotification("倒數 $left 秒…請切到要測的 App")
                    toast("倒數 $left 秒")
                    left--
                    countdownRunnable = this
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    countdownRunnable = null
                    when (action) {
                        ACTION_DELAYED_SCREENSHOT -> {
                            updateNotification("正在截圖…")
                            workerHandler?.post { captureScreenshot(resultCode, resultData) }
                        }
                        ACTION_DELAYED_RECORD -> {
                            updateNotification("錄影中…點通知可停止")
                            workerHandler?.post { startRecording(resultCode, resultData) }
                        }
                    }
                }
            }
        }
        countdownRunnable = tick
        mainHandler.post(tick)
    }

    private fun obtainProjection(resultCode: Int, resultData: Intent): MediaProjection? {
        mediaProjection?.stop()
        mediaProjection = null
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData) ?: return null
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (isRecording) stopRecordingAndSave()
                    cleanupDisplay()
                }
            }
        }, mainHandler)
        mediaProjection = projection
        return projection
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

    private fun captureScreenshot(resultCode: Int, resultData: Intent) {
        try {
            val projection = obtainProjection(resultCode, resultData)
            if (projection == null) {
                toast("無法取得 MediaProjection")
                finishService()
                return
            }
            val (width, height, density) = screenMetrics()
            // 對齊偶數，部分裝置較穩
            val w = width and 1.inv()
            val h = height and 1.inv()

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "overlaytester-shot",
                w,
                h,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )

            // 等幾幀再抓，避免黑圖
            Thread.sleep(400)
            var image = imageReader?.acquireLatestImage()
            if (image == null) {
                Thread.sleep(300)
                image = imageReader?.acquireLatestImage()
            }
            if (image == null) {
                toast("截圖失敗：沒拿到畫面（可能被 FLAG_SECURE 擋）")
                cleanupDisplay()
                projection.stop()
                mediaProjection = null
                finishService()
                return
            }

            val bitmap = imageToBitmap(image)
            image.close()
            val uriOrPath = saveBitmap(bitmap)
            bitmap.recycle()

            cleanupDisplay()
            projection.stop()
            mediaProjection = null

            mainHandler.post {
                toast(
                    if (uriOrPath != null) "截圖已存：${uriOrPath}"
                    else "截圖儲存失敗"
                )
                finishService()
            }
        } catch (e: Exception) {
            mainHandler.post {
                toast("截圖失敗: ${e.message}")
                cleanupAll()
                finishService()
            }
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        try {
            if (isRecording) return
            val projection = obtainProjection(resultCode, resultData)
            if (projection == null) {
                toast("無法取得 MediaProjection")
                finishService()
                return
            }
            val (width, height, density) = screenMetrics()
            var w = width and 1.inv()
            var h = height and 1.inv()
            // 過大解析度在部分機型會 prepare 失敗，上限約 1080p 寬
            if (w > 1080) {
                val scale = 1080f / w
                w = 1080
                h = (h * scale).toInt() and 1.inv()
            }

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
                "overlaytester-rec",
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
                updateNotification("錄影中…點此停止")
                toast("開始錄影")
            }
        } catch (e: Exception) {
            mainHandler.post {
                toast("錄影開始失敗: ${e.message}")
                cleanupAll()
                finishService()
            }
        }
    }

    private fun stopRecordingAndSave() {
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
                mediaProjection?.stop()
                mediaProjection = null

                val file = recordingFile
                val saved = if (file != null && file.exists()) {
                    publishVideoToGallery(file)
                } else null

                mainHandler.post {
                    toast(
                        if (saved != null) "錄影已存：$saved"
                        else "錄影儲存失敗（若畫面全黑，可能被 FLAG_SECURE 擋）"
                    )
                    finishService()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    toast("停止錄影失敗: ${e.message}")
                    cleanupAll()
                    finishService()
                }
            }
        }
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
        val name = "OverlayTester_${timestamp()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OverlayTester")
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
                "OverlayTester"
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
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "OverlayTester")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "OverlayTester_${timestamp()}.mp4")
    }

    private fun publishVideoToGallery(file: File): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/OverlayTester"
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
            // 已進相簿可刪 app 私有複本
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

    private fun finishService() {
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
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OverlayTester 擷取")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(0, "停止錄影", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        cleanupAll()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_DELAYED_SCREENSHOT = "com.example.overlaytester.DELAYED_SCREENSHOT"
        const val ACTION_DELAYED_RECORD = "com.example.overlaytester.DELAYED_RECORD"
        const val ACTION_STOP_RECORDING = "com.example.overlaytester.STOP_RECORDING"
        const val ACTION_CANCEL = "com.example.overlaytester.CANCEL"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DELAY_SEC = "delay_sec"

        const val DEFAULT_DELAY_SEC = 5
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 42

        fun startDelayedScreenshot(context: Context, resultCode: Int, data: Intent, delaySec: Int = DEFAULT_DELAY_SEC) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_DELAYED_SCREENSHOT
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_DELAY_SEC, delaySec)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startDelayedRecord(context: Context, resultCode: Int, data: Intent, delaySec: Int = DEFAULT_DELAY_SEC) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_DELAYED_RECORD
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_DELAY_SEC, delaySec)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }
}
