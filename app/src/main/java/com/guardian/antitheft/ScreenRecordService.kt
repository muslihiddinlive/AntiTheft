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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ekranni yozib oladi. Kamera overlay yo'q — faqat sof ekran yozuvi.
 *   sendToTelegram = false → filesDir/recordings/ ga saqlaydi, Telegram'ga ketmaydi
 *   sendToTelegram = true  → saqlaydi + Telegram'ga ham yuboradi (bot buyrug'i)
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private var recordDurationMs = DEFAULT_DURATION_S * 1000L
    private var sendToTelegram = false

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
                sendToTelegram    = intent.getBooleanExtra(EXTRA_SEND_TELEGRAM, false)
                recordDurationMs  = intent.getIntExtra(EXTRA_DURATION_SEC, DEFAULT_DURATION_S)
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
    }

    // ── Yozuv ────────────────────────────────────────────────────────

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
                "AntiTheftSR", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface, null, null
            )
            mediaRecorder!!.start()
            isRunning = true
        } catch (e: Exception) { e.printStackTrace(); releaseAll(); stopSelf(); return }

        stopHandler.postDelayed({ finishRecording() }, recordDurationMs)
    }

    private fun finishRecording() {
        if (!isRunning) { stopSelf(); return }
        isRunning = false
        stopHandler.removeCallbacksAndMessages(null)

        try { mediaRecorder?.stop()    } catch (e: Exception) { }
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop()   } catch (e: Exception) { }
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
                // sendToTelegram=false: ilovada saqlanib qoladi
            }
            stopSelf()
        }.start()
    }

    private fun releaseAll() {
        try { mediaRecorder?.stop()    } catch (e: Exception) { }
        try { mediaRecorder?.release() } catch (e: Exception) { }
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { mediaProjection?.stop()   } catch (e: Exception) { }
        mediaProjection = null
        outputFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Ekran yozuvi", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ekran yozilmoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
