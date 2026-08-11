package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * Yon tugma undov (gesture) orqali chaqiriladi: qisqa audio yozib,
 * Telegram botiga yuboradi. Ekran o'chiq holatda ham ishlaydi
 * (VolumeKeyAccessibilityService orqali chaqirilgani uchun).
 */
class AudioRecordService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "antitheft_audio_channel"
        private const val NOTIF_ID = 46
        private const val RECORD_DURATION_MS = 8_000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startRecording()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id", "").orEmpty()
        if (token.isEmpty() || chatId.isEmpty()) { stopSelf(); return }

        outputFile = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")

        try {
            recorder = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()
            ).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        handler.postDelayed({ finishRecording() }, RECORD_DURATION_MS)
    }

    private fun finishRecording() {
        try { recorder?.stop() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null

        val file = outputFile
        val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id", "").orEmpty()

        Thread {
            if (file != null && file.exists() && token.isNotEmpty() && chatId.isNotEmpty()) {
                TelegramSender.sendAudio(token, chatId, file.readBytes(), "🎙️ Ovoz yozuvi (yon tugma)")
                file.delete()
            }
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        outputFile?.let { if (it.exists()) it.delete() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Audio xizmati", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Anti-theft ovoz yozish xizmati" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Ishlamoqda...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
