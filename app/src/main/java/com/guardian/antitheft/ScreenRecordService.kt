package com.guardian.antitheft

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ekran va (ixtiyoriy) old kamera yozuvi — paralel, UI'da hech narsa ko'rinmaydi.
 *
 * Kamera yozuvi: Camera2 → MediaRecorder.surface → cam_TIMESTAMP.mp4
 * Ekran yozuvi : MediaProjection → VirtualDisplay → screen_TIMESTAMP.mp4
 *
 * EXTRA_WITH_CAMERA = true bo'lsa ikkala fayl ham yoziladi.
 * EXTRA_SEND_TELEGRAM = true bo'lsa ikkala fayl ham Telegram'ga yuboriladi.
 * Aks holda: ilovaning filesDir/recordings/ papkasida saqlanadi.
 */
class ScreenRecordService : Service() {

    // ── Ekran ────────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenRecorder: MediaRecorder? = null
    private var screenFile: File? = null

    // ── Kamera ───────────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraRecorder: MediaRecorder? = null
    private var cameraFile: File? = null
    private var cameraSurface: Surface? = null
    private val cameraThread = HandlerThread("CamRec").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // ── Umumiy ───────────────────────────────────────────────────────
    private val stopHandler = Handler(Looper.getMainLooper())
    private var recordDurationMs = DEFAULT_DURATION_S * 1000L
    private var sendToTelegram   = false
    private var withCamera       = false

    companion object {
        const val ACTION_START        = "action_start"
        const val ACTION_STOP         = "action_stop"
        const val EXTRA_CODE          = "extra_code"
        const val EXTRA_DATA          = "extra_data"
        const val EXTRA_DURATION_SEC  = "duration_sec"
        const val EXTRA_SEND_TELEGRAM = "send_telegram"
        const val EXTRA_WITH_CAMERA   = "with_camera"

        private const val CHANNEL_ID         = "antitheft_screenrec_channel"
        private const val NOTIF_ID           = 46
        private const val DEFAULT_DURATION_S = 20
        const val MAX_DURATION_S             = 18_000
        private const val SCALE              = 0.6

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
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                sendToTelegram   = intent.getBooleanExtra(EXTRA_SEND_TELEGRAM, false)
                withCamera       = intent.getBooleanExtra(EXTRA_WITH_CAMERA, false)
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
        releaseAll()
        cameraThread.quitSafely()
    }

    // ── Yozuvni boshlash ─────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try { mpm.getMediaProjection(resultCode, data) } catch (e: Exception) { null }
        if (proj == null) { stopSelf(); return }
        mediaProjection = proj

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = ((metrics.widthPixels  * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        val h = ((metrics.heightPixels * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)

        val dir = File(filesDir, "recordings").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        screenFile = File(dir, "screen_$ts.mp4")
        if (withCamera) cameraFile = File(dir, "cam_$ts.mp4")

        // Ekran MediaRecorder
        try {
            screenRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(screenFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) { e.printStackTrace(); stopSelf(); return }

        // VirtualDisplay
        try {
            virtualDisplay = proj.createVirtualDisplay(
                "AntiTheftSR", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenRecorder!!.surface, null, null
            )
            screenRecorder!!.start()
            isRunning = true
        } catch (e: Exception) { e.printStackTrace(); releaseAll(); stopSelf(); return }

        // Kamera — parallel, ekranda hech narsa ko'rinmaydi
        if (withCamera) startCameraRecording()

        stopHandler.postDelayed({ finishRecording() }, recordDurationMs)
    }

    @SuppressLint("MissingPermission")
    private fun startCameraRecording() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        val orientation = cm.getCameraCharacteristics(frontId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

        try {
            cameraRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(480, 640)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(1_500_000)
                setOrientationHint(orientation)
                setOutputFile(cameraFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) { e.printStackTrace(); return }

        cameraSurface = cameraRecorder!!.surface

        try {
            cm.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val surface = cameraSurface ?: return
                    try {
                        cam.createCaptureSession(listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    cameraSession = s
                                    try {
                                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                            .apply {
                                                addTarget(surface)
                                                set(CaptureRequest.CONTROL_AE_MODE,
                                                    CaptureRequest.CONTROL_AE_MODE_ON)
                                            }.build()
                                        s.setRepeatingRequest(req, null, cameraHandler)
                                        cameraRecorder?.start()
                                    } catch (e: Exception) { e.printStackTrace() }
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

    // ── Yakunlash ────────────────────────────────────────────────────

    private fun finishRecording() {
        if (!isRunning) { stopSelf(); return }
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)

        // Kamera oldin to'xtatiladi
        try { cameraSession?.stopRepeating() } catch (e: Exception) { }
        try { cameraSession?.close()         } catch (e: Exception) { }
        cameraSession = null
        try { cameraDevice?.close()          } catch (e: Exception) { }
        cameraDevice = null
        try { cameraRecorder?.stop()         } catch (e: Exception) { }
        try { cameraRecorder?.release()      } catch (e: Exception) { }
        cameraRecorder = null
        cameraSurface?.release(); cameraSurface = null

        // Keyin ekran
        try { screenRecorder?.stop()         } catch (e: Exception) { }
        try { screenRecorder?.release()      } catch (e: Exception) { }
        screenRecorder = null
        try { virtualDisplay?.release()      } catch (e: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop()        } catch (e: Exception) { }
        mediaProjection = null

        val sFile = screenFile
        val cFile = cameraFile
        val prefs  = SecurePrefs.get(this)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()
        val durSec = recordDurationMs / 1000

        Thread {
            if (sendToTelegram && token.isNotEmpty() && chatId.isNotEmpty()) {
                if (sFile != null && sFile.exists() && sFile.length() > 0) {
                    val sz = "%.1f MB".format(sFile.length() / 1024.0 / 1024.0)
                    TelegramSender.sendVideo(token, chatId, sFile.readBytes(),
                        "🖥️ Ekran ($durSec s, $sz)")
                }
                if (cFile != null && cFile.exists() && cFile.length() > 0) {
                    val sz = "%.1f MB".format(cFile.length() / 1024.0 / 1024.0)
                    TelegramSender.sendVideo(token, chatId, cFile.readBytes(),
                        "📷 Kamera ($durSec s, $sz)")
                }
            }
            stopSelf()
        }.start()
    }

    private fun releaseAll() {
        try { cameraSession?.close()    } catch (e: Exception) { }; cameraSession = null
        try { cameraDevice?.close()     } catch (e: Exception) { }; cameraDevice = null
        try { cameraRecorder?.stop()    } catch (e: Exception) { }
        try { cameraRecorder?.release() } catch (e: Exception) { }; cameraRecorder = null
        cameraSurface?.release(); cameraSurface = null
        try { screenRecorder?.stop()    } catch (e: Exception) { }
        try { screenRecorder?.release() } catch (e: Exception) { }; screenRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }; virtualDisplay = null
        try { mediaProjection?.stop()   } catch (e: Exception) { }; mediaProjection = null
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Ekran yozuvi", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText(if (withCamera) "Ekran + kamera yozilmoqda..." else "Ekran yozilmoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
