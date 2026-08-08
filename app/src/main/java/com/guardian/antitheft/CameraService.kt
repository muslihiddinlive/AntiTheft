package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*

/**
 * Foreground Service:
 *  1. Old kameradan sezilmasdan JPEG suratini oladi (preview yo'q)
 *  2. TelegramSender orqali foydalanuvchi botiga yuboradi
 *  3. Tugatadi
 */
class CameraService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null

    companion object {
        private const val CHANNEL_ID  = "antitheft_fg_channel"
        private const val NOTIF_ID    = 42
        private const val IMG_WIDTH   = 640
        private const val IMG_HEIGHT  = 480
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("CameraWorker").also { it.start() }
        cameraHandler = Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        captureAndSend()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
        handlerThread.quitSafely()
    }

    override fun onBind(intent: Intent?) = null

    // ──────────────────────────────────────────────
    // Asosiy logika
    // ──────────────────────────────────────────────

    private fun captureAndSend() {
        val prefs  = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()

        if (token.isEmpty() || chatId.isEmpty()) { stopSelf(); return }

        val manager  = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = findFrontCamera(manager) ?: run { stopSelf(); return }

        imageReader = ImageReader.newInstance(IMG_WIDTH, IMG_HEIGHT, ImageFormat.JPEG, 1)

        // Rasm tayyor bo'lganda ishga tushadi
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes  = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            releaseCamera()

            // Tarmoq → background thread (main thread emas)
            Thread {
                TelegramSender.sendPhoto(
                    token  = token,
                    chatId = chatId,
                    photo  = bytes,
                    caption = "⚠️ Noto'g'ri parol urinishi!"
                )
                stopSelf()
            }.start()

        }, cameraHandler)

        // Kamerani och
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); stopSelf()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); stopSelf()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            // CAMERA ruxsati berilmagan
            stopSelf()
        } catch (e: CameraAccessException) {
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice) {
        try {
            // API 28: deprecated bo'lsa-da barcha versiyalarda ishlaydi.
            // API 33+ da createCaptureSession(SessionConfiguration) ishlatilishi mumkin.
            camera.createCaptureSession(
                listOf(imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE
                            ).apply {
                                addTarget(imageReader!!.surface)
                                set(CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON)
                                // Flash o'chiriq — sezilmasin
                                set(CaptureRequest.FLASH_MODE,
                                    CaptureRequest.FLASH_MODE_OFF)
                            }.build()
                            session.capture(request, null, cameraHandler)
                        } catch (e: CameraAccessException) {
                            stopSelf()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        stopSelf()
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            stopSelf()
        }
    }

    // ──────────────────────────────────────────────
    // Yordamchi funksiyalar
    // ──────────────────────────────────────────────

    /** Old kamera ID'sini qaytaradi; topilmasa birinchi kamerani qaytaradi */
    private fun findFrontCamera(manager: CameraManager): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: CameraAccessException) { null }
    }

    private fun releaseCamera() {
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Xavfsizlik xizmati",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Anti-theft fon xizmati" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ishlamoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
