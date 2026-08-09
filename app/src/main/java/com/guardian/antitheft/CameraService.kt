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
import android.util.Size

/**
 * Foreground Service:
 *  1. Old kameradan sezilmasdan JPEG suratini oladi (preview yo'q)
 *  2. Suratga olishdan oldin AE/AF konvergensiyasi uchun yashirin "isinish" kadrlarini o'tkazadi
 *  3. TelegramSender orqali foydalanuvchi botiga yuboradi
 *  4. Tugatadi
 */
class CameraService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewReader: ImageReader? = null
    private var warmupFrameCount = 0
    private var stillTriggered = false

    companion object {
        private const val CHANNEL_ID  = "antitheft_fg_channel"
        private const val NOTIF_ID    = 42
        private const val TARGET_WIDTH   = 640
        private const val TARGET_HEIGHT  = 480
        // AE/AF konvergensiyasi uchun kutiladigan minimal preview kadrlar soni
        private const val MIN_WARMUP_FRAMES = 20
        // Konvergensiya sodir bo'lmasa ham shu vaqtdan keyin majburan suratga olinadi
        private const val WARMUP_TIMEOUT_MS = 1500L
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

        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: CameraAccessException) {
            stopSelf(); return
        }
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: run { stopSelf(); return }

        val jpegSize = pickBestSize(map.getOutputSizes(ImageFormat.JPEG))
        val previewSize = pickBestSize(map.getOutputSizes(ImageFormat.YUV_420_888))

        imageReader = ImageReader.newInstance(
            jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1
        )
        // Faqat AE/AF isinishi uchun — kadrlar darhol tashlab yuboriladi
        previewReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes  = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            releaseCamera()

            val saveToGallery = prefs.getBoolean("save_to_gallery", true)
            val autoResend     = prefs.getBoolean("auto_resend", true)
            val caption        = "⚠️ Noto'g'ri parol urinishi!"

            // Tarmoq/disk ishlari → background thread (main thread emas)
            Thread {
                if (saveToGallery) {
                    GallerySaver.save(applicationContext, bytes)
                }

                val sent = TelegramSender.sendPhoto(
                    token   = token,
                    chatId  = chatId,
                    photo   = bytes,
                    caption = caption
                )

                if (!sent && autoResend) {
                    // Internet yo'q yoki xato — keyinroq yuborish uchun navbatga qo'yamiz
                    PendingQueue.enqueue(applicationContext, bytes, caption)
                    PendingSendService.start(applicationContext)
                }

                stopSelf()
            }.start()
        }, cameraHandler)

        // Preview kadrlarini shunchaki tashlab yuboramiz — faqat sensor "isishi" uchun kerak
        previewReader!!.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        }, cameraHandler)

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
            camera.createCaptureSession(
                listOf(previewReader!!.surface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startWarmupPreview(camera, session)
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

    /** AE/AF konvergensiyasi uchun repeating preview so'rovlarini boshlaydi */
    private fun startWarmupPreview(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            val previewRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            warmupFrameCount = 0
            stillTriggered = false

            session.setRepeatingRequest(
                previewRequest,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        warmupFrameCount++
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val aeReady = aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

                        if (!stillTriggered && warmupFrameCount >= MIN_WARMUP_FRAMES && aeReady) {
                            stillTriggered = true
                            session.stopRepeating()
                            takeStillCapture(camera, session)
                        }
                    }
                },
                cameraHandler
            )

            // AE hech qachon konvergensiyaga yetmasa ham majburan suratga olish
            cameraHandler.postDelayed({
                if (!stillTriggered) {
                    stillTriggered = true
                    try {
                        session.stopRepeating()
                    } catch (e: Exception) { /* session allaqachon yopilgan bo'lishi mumkin */ }
                    takeStillCapture(camera, session)
                }
            }, WARMUP_TIMEOUT_MS)

        } catch (e: CameraAccessException) {
            stopSelf()
        }
    }

    private fun takeStillCapture(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            val stillRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Flash o'chiriq — sezilmasin
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }.build()
            session.capture(stillRequest, null, cameraHandler)
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

    /** TARGET o'lchamiga eng yaqin qo'llab-quvvatlanadigan o'lchamni tanlaydi */
    private fun pickBestSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(TARGET_WIDTH, TARGET_HEIGHT)
        return sizes.minByOrNull { size ->
            val areaDiff = kotlin.math.abs(
                (size.width.toLong() * size.height) - (TARGET_WIDTH.toLong() * TARGET_HEIGHT)
            )
            areaDiff
        } ?: sizes[0]
    }

    private fun releaseCamera() {
        try { captureSession?.close() } catch (e: Exception) { }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewReader?.close()
        previewReader = null
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
