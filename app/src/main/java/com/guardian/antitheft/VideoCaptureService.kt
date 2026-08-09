package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import java.io.File

/**
 * Old kameradan ovozsiz, qisqa (bir necha soniyalik) video clip yozib oladi
 * va Telegram botiga yuboradi. AE/AF isinishi CameraService bilan bir xil.
 */
class VideoCaptureService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var warmupFrameCount = 0
    private var recordingStarted = false

    companion object {
        private const val CHANNEL_ID = "antitheft_video_channel"
        private const val NOTIF_ID = 44
        private const val MIN_WARMUP_FRAMES = 15
        private const val WARMUP_TIMEOUT_MS = 1500L
        private const val RECORD_DURATION_MS = 4000L
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("VideoWorker").also { it.start() }
        cameraHandler = Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startCapture()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        handlerThread.quitSafely()
    }

    override fun onBind(intent: Intent?) = null

    private fun startCapture() {
        val prefs  = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()
        if (token.isEmpty() || chatId.isEmpty()) { stopSelf(); return }

        val manager  = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: CameraAccessException) { null } ?: run { stopSelf(); return }

        val (width, height) = pickVideoSize(cameraId)

        outputFile = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")

        try {
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(20)
                setVideoEncodingBitRate(1_000_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf(); return
        }

        previewReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, cameraHandler)
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); stopSelf() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); stopSelf() }
            }, cameraHandler)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createSession(camera: CameraDevice) {
        try {
            val surfaces = listOf(previewReader!!.surface, mediaRecorder!!.surface)
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startWarmup(camera, session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { stopSelf() }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startWarmup(camera: CameraDevice, session: CameraCaptureSession) {
        try {
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            warmupFrameCount = 0
            session.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    warmupFrameCount++
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    val aeReady = aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                    if (!recordingStarted && warmupFrameCount >= MIN_WARMUP_FRAMES && aeReady) {
                        beginRecording(camera, session)
                    }
                }
            }, cameraHandler)

            cameraHandler.postDelayed({
                if (!recordingStarted) beginRecording(camera, session)
            }, WARMUP_TIMEOUT_MS)

        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun beginRecording(camera: CameraDevice, session: CameraCaptureSession) {
        if (recordingStarted) return
        recordingStarted = true
        try {
            session.stopRepeating()

            val recordRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(mediaRecorder!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            mediaRecorder!!.start()
            session.setRepeatingRequest(recordRequest, null, cameraHandler)

            cameraHandler.postDelayed({ finishRecording() }, RECORD_DURATION_MS)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun finishRecording() {
        try { captureSession?.stopRepeating() } catch (e: Exception) { }
        try { mediaRecorder?.stop() } catch (e: Exception) { }
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        releaseCameraOnly()

        val file = outputFile
        val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()

        Thread {
            if (file != null && file.exists() && token.isNotEmpty() && chatId.isNotEmpty()) {
                TelegramSender.sendVideo(token, chatId, file.readBytes(), "🎥 Video (4 soniya)")
                file.delete()
            }
            stopSelf()
        }.start()
    }

    private fun pickVideoSize(cameraId: String): Pair<Int, Int> {
        return try {
            val numericId = cameraId.toIntOrNull()
            if (numericId != null && CamcorderProfile.hasProfile(numericId, CamcorderProfile.QUALITY_LOW)) {
                val profile = CamcorderProfile.get(numericId, CamcorderProfile.QUALITY_LOW)
                profile.videoFrameWidth to profile.videoFrameHeight
            } else {
                640 to 480
            }
        } catch (e: Exception) {
            640 to 480
        }
    }

    private fun releaseCameraOnly() {
        try { captureSession?.close() } catch (e: Exception) { }
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewReader?.close()
        previewReader = null
    }

    private fun releaseAll() {
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        releaseCameraOnly()
        outputFile?.let { if (it.exists()) it.delete() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Video xizmati", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Anti-theft video yozish xizmati" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ishlamoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
