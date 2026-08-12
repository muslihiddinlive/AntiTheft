package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * Telegram bot buyruqlarini kuzatib turadi (long polling).
 *
 * Arxitektura:
 *  - pollThread  : Telegram'dan yangi update'larni oladi va commandQueue'ga qo'shadi
 *  - workerThread: commandQueue'dan buyruqlarni bittadan o'qib, ketma-ket bajaradi
 *
 * Shu tufayli bir buyruq tugamay ikkinchisi kelsa ham conflict bo'lmaydi —
 * navbatga tushadi va o'z vaqtida bajariladi.
 */
class BotCommandListener : Service() {

    // Triple<text, token, chatId>
    private val commandQueue = LinkedBlockingQueue<Triple<String, String, String>>(50)

    @Volatile private var running = false
    private var pollThread:   Thread? = null
    private var workerThread: Thread? = null

    companion object {
        private const val CHANNEL_ID       = "antitheft_bot_channel"
        private const val NOTIF_ID         = 45
        private const val POLL_TIMEOUT_SEC = 25

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BotCommandListener::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!running) {
            running = true
            startThreads()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        // workerThread'ni uyg'otish uchun poison pill
        commandQueue.offer(Triple("__STOP__", "", ""))
        pollThread?.interrupt()
        workerThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Thread boshqaruvi ──────────────────────────────────────────────────────

    private fun startThreads() {
        pollThread = Thread({ pollLoop() }, "BotPoll").apply {
            isDaemon = true
            start()
        }
        workerThread = Thread({ workerLoop() }, "BotWorker").apply {
            isDaemon = true
            start()
        }
    }

    // ── Poll loop (faqat update oladi, hech narsa bajarmaydi) ─────────────────

    private fun pollLoop() {
        val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)

        while (running) {
            val enabled = prefs.getBoolean("remote_commands", false)
            val token   = prefs.getString("bot_token", "").orEmpty()
            val chatId  = prefs.getString("chat_id", "").orEmpty()

            if (!enabled || token.isEmpty() || chatId.isEmpty()) {
                running = false
                stopSelf()
                return
            }

            val lastUpdateId = prefs.getLong("last_update_id", 0L)

            try {
                val response = getUpdates(token, lastUpdateId + 1) ?: continue
                val results  = response.getJSONArray("result")

                for (i in 0 until results.length()) {
                    val update     = results.getJSONObject(i)
                    val updateId   = update.getLong("update_id")
                    val message    = update.optJSONObject("message")
                    val fromChatId = message?.optJSONObject("chat")?.optString("id")
                    val text       = message?.optString("text")?.trim()

                    if (fromChatId == chatId && !text.isNullOrEmpty()) {
                        // Navbat to'liq bo'lsa yangi buyruqni tashlaymiz (xavfsizlik)
                        if (!commandQueue.offer(Triple(text, token, chatId))) {
                            TelegramSender.sendMessage(token, chatId,
                                "⚠️ Navbat to'liq, buyruq qabul qilinmadi. Biroz kuting.")
                        }
                    }
                    prefs.edit().putLong("last_update_id", updateId).apply()
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
                safeSleep(5_000)
            }
        }
    }

    // ── Worker loop (navbatdagi buyruqlarni bittadan bajaradi) ────────────────

    private fun workerLoop() {
        while (running) {
            try {
                val (text, token, chatId) = commandQueue.take()
                if (text == "__STOP__") break
                executeCommand(text, token, chatId)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Buyruq bajaruvchi ─────────────────────────────────────────────────────

    private fun executeCommand(text: String, token: String, chatId: String) {
        val cmd = text.lowercase().trim()

        // /video alohida — duration parametr oladi (/video 30)
        if (cmd.startsWith("/video")) {
            val parts       = cmd.split("\\s+".toRegex())
            val durationSec = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(3, 60) ?: 15
            val videoIntent = Intent(this, VideoCaptureService::class.java)
                .putExtra(VideoCaptureService.EXTRA_DURATION_SEC, durationSec)
            startForegroundService(videoIntent)
            TelegramSender.sendMessage(token, chatId, "🎥 Video yozilmoqda ($durationSec soniya)...")
            return
        }

        when (cmd) {
            "/alarm", "/signal" -> {
                startForegroundService(Intent(this, AlarmService::class.java))
                TelegramSender.sendMessage(token, chatId, "🔊 Signal chalindi!")
            }
            "/status" -> {
                val statusText = DeviceStatusHelper.getStatusText(this)
                val locText    = LocationHelper.getLastLocationText(this)
                TelegramSender.sendMessage(token, chatId, "$statusText\n$locText")
            }
            "/location", "/loc" -> {
                val locText = LocationHelper.getLastLocationText(this)
                TelegramSender.sendMessage(token, chatId, locText)
            }
            "/photo", "/snap" -> {
                startForegroundService(Intent(this, CameraService::class.java))
                TelegramSender.sendMessage(token, chatId, "📸 Surat olinmoqda...")
            }
            "/audio", "/listen" -> {
                startForegroundService(Intent(this, AudioRecordService::class.java))
                TelegramSender.sendMessage(token, chatId, "🎙️ Audio yozilmoqda...")
            }
            "/help" -> {
                val help = """
                    🛡️ AntiTheft Guard buyruqlari:
                    /status — qurilma holati + joylashuv
                    /location — faqat joylashuv
                    /photo — surat ol (old + orqa)
                    /video [soniya] — video yoz (masalan: /video 30, default 15s)
                    /audio — ovoz yozish
                    /alarm — jiringlatish
                    /lock — ekranni darhol qulflash
                    /help — shu ro'yxat
                """.trimIndent()
                TelegramSender.sendMessage(token, chatId, help)
            }
            "/lock" -> {
                val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val admin = android.content.ComponentName(this, AdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) {
                    TelegramSender.sendMessage(token, chatId, "🔒 Qurilma qulflanyapti...")
                    dpm.lockNow()
                } else {
                    TelegramSender.sendMessage(token, chatId,
                        "⚠️ Device Admin yoqilmagan. Ilovani ochib Device Admin'ni yoqing.")
                }
            }
        }
    }

    // ── Yordamchi ─────────────────────────────────────────────────────────────

    private fun getUpdates(token: String, offset: Long): org.json.JSONObject? {
        val url  = URL("https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=$POLL_TIMEOUT_SEC")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = (POLL_TIMEOUT_SEC + 10) * 1_000
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun safeSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
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
