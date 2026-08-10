package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telegram bot buyruqlarini kuzatib turadi (long polling).
 * Hozircha: /alarm — ovozli signal chalish.
 * Keyingi bosqichda: /lock, /wipe (PIN tasdig'i bilan) qo'shiladi.
 */
class BotCommandListener : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    @Volatile private var running = false

    companion object {
        private const val CHANNEL_ID = "antitheft_bot_channel"
        private const val NOTIF_ID = 45
        private const val POLL_TIMEOUT_SEC = 25

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BotCommandListener::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("BotListener").also { it.start() }
        handler = Handler(thread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!running) {
            running = true
            handler.post { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        thread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)

        while (running) {
            val enabled = prefs.getBoolean("remote_commands", false)
            val token   = prefs.getString("bot_token", "").orEmpty()
            val chatId  = prefs.getString("chat_id", "").orEmpty()

            if (!enabled || token.isEmpty() || chatId.isEmpty()) {
                // O'chirilgan bo'lsa, xizmatni to'xtatamiz — internet/batareyani tejash uchun
                running = false
                stopSelf()
                return
            }

            val lastUpdateId = prefs.getLong("last_update_id", 0L)

            try {
                val response = getUpdates(token, lastUpdateId + 1)
                if (response != null) {
                    val results = response.getJSONArray("result")
                    for (i in 0 until results.length()) {
                        val update = results.getJSONObject(i)
                        val updateId = update.getLong("update_id")

                        val message = update.optJSONObject("message")
                        val fromChatId = message?.optJSONObject("chat")?.optString("id")
                        val text = message?.optString("text")?.trim()

                        if (fromChatId == chatId && text != null) {
                            handleCommand(text, token, chatId)
                        }

                        prefs.edit().putLong("last_update_id", updateId).apply()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Internet yo'q yoki xato — biroz kutib qayta urinamiz
                Thread.sleep(5000)
            }
        }
    }

    private fun handleCommand(text: String, token: String, chatId: String) {
        when (text.lowercase()) {
            "/alarm", "/signal" -> {
                startService(Intent(this, AlarmService::class.java))
                TelegramSender.sendMessage(token, chatId, "🔊 Signal chalindi!")
            }
            "/status" -> {
                val statusText = DeviceStatusHelper.getStatusText(this)
                val locText = LocationHelper.getLastLocationText(this)
                TelegramSender.sendMessage(token, chatId, "$statusText\n$locText")
            }
            // /lock va /wipe keyingi bosqichda PIN tasdig'i bilan qo'shiladi
        }
    }

    private fun getUpdates(token: String, offset: Long): org.json.JSONObject? {
        val url = URL(
            "https://api.telegram.org/bot$token/getUpdates" +
                "?offset=$offset&timeout=$POLL_TIMEOUT_SEC"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = (POLL_TIMEOUT_SEC + 10) * 1000
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            org.json.JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Uzoqdan boshqarish", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Telegram buyruqlarini kuzatish" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Uzoqdan boshqarish faol")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
