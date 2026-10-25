package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

/**
 * Ekranni MediaProjection orqali yozib oladi va Telegram botiga yuboradi.
 * FuizRec (muslihiddinlive/FuizRec) loyihasidagi ScreenRecordService asosida,
 * AntiTheft'ning mavjud yuborish infratuzilmasiga (SecurePrefs + TelegramSender)
 * moslab qayta yozilgan — VideoCaptureService bilan bir xil naqsh.
 *
 * MUHIM: MediaProjection ruxsati har safar tizim dialogi orqali so'raladi —
 * bu Android'ning o'z xavfsizlik siyosati, dastur ichidan chetlab o'tib bo'lmaydi.
 * Shuning uchun bu xizmat to'g'ridan-to'g'ri emas, ScreenRecordPermissionActivity
 * orqali ishga tushiriladi.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private var recordDurationMs = DEFAULT_DURATION_S * 1000L

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP  = "action_stop"
        const val EXTRA_CODE          = "extra_code"
        const val EXTRA_DATA          = "extra_data"
        const val EXTRA_DURATION_SEC  = "duration_sec"

        private const val CHANNEL_ID  = "antitheft_screenrec_channel"
        private const val NOTIF_ID    = 46
        private const val DEFAULT_DURATION_S = 20
        private const val MAX_DURATION_S     = 60
        // Fayl hajmini va eski qurilmalardagi encoder xatolarini kamaytirish uchun masshtab
        private const val SCALE = 0.6

        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                finishRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val reqSec = intent.getIntExtra(EXTRA_DURATION_SEC, DEFAULT_DURATION_S)
                recordDurationMs = reqSec.coerceIn(5, MAX_DURATION_S) * 1000L

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
    }

    // ── Asosiy logika ───────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
        if (projection == null) { stopSelf(); return }
        mediaProjection = projection

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width  = ((metrics.widthPixels  * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        val height = ((metrics.heightPixels * SCALE).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        val density = metrics.densityDpi

        outputFile = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4")

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
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf(); return
        }

        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "AntiTheftScreenRec",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface, null, null
            )
            mediaRecorder!!.start()
            isRunning = true
        } catch (e: Exception) {
            e.printStackTrace()
            releaseAll()
            stopSelf(); return
        }

        stopHandler.postDelayed({ finishRecording() }, recordDurationMs)
    }

    private fun finishRecording() {
        if (!isRunning) { stopSelf(); return }
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)

        try { mediaRecorder?.stop() } catch (e: Exception) { }
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null

        val file  = outputFile
        val prefs = SecurePrefs.get(this)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()
        val durationSecSnapshot = recordDurationMs / 1000

        Thread {
            val sizeMb = "%.2f".format((file?.length() ?: 0) / 1024.0 / 1024.0)
            when {
                file == null || !file.exists() ->
                    TelegramSender.sendMessage(token, chatId, "⚠️ Ekran yozuvi fayli topilmadi.")
                file.length() == 0L -> {
                    TelegramSender.sendMessage(token, chatId, "⚠️ Ekran yozuvi bo'sh chiqdi.")
                    file.delete()
                }
                token.isEmpty() || chatId.isEmpty() -> file.delete()
                else -> {
                    val caption = "🖥️ Ekran yozuvi (${durationSecSnapshot} soniya, $sizeMb MB)"
                    val sent = TelegramSender.sendVideo(token, chatId, file.readBytes(), caption)
                    if (!sent) {
                        TelegramSender.sendMessage(token, chatId,
                            "⚠️ Ekran yozuvi ($sizeMb MB) yuborishda xatolik. Tarmoq sekin yoki fayl katta.")
                    }
                    file.delete()
                }
            }
            stopSelf()
        }.start()
    }

    private fun releaseAll() {
        try { mediaRecorder?.stop() } catch (e: Exception) { }
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null
        outputFile?.let { if (it.exists()) it.delete() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Ekran yozuvi", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Anti-theft ekran yozish xizmati" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ekran yozilmoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
