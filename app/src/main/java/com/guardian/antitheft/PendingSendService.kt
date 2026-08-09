package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder

/**
 * Internet yo'q bo'lganda navbatga qo'yilgan suratlarni kuzatib turadi.
 * Tarmoq qaytishi bilan navbatdagilarni Telegram'ga yuborishga urinadi.
 * Barchasi yuborilgach — o'zini to'xtatadi.
 */
class PendingSendService : Service() {

    companion object {
        private const val CHANNEL_ID = "antitheft_pending_channel"
        private const val NOTIF_ID = 43

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PendingSendService::class.java))
        }
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var flushing = false

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        if (!PendingQueue.hasPending(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Internet allaqachon bor bo'lsa — darhol urinib ko'ramiz
        tryFlush()

        // Internet yo'q bo'lsa, qaytishini kutamiz
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                tryFlush()
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    private fun tryFlush() {
        if (flushing) return
        flushing = true
        Thread {
            val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("bot_token", "").orEmpty()
            val chatId = prefs.getString("chat_id", "").orEmpty()

            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                val items = PendingQueue.listAll(this)
                for (item in items) {
                    val ok = TelegramSender.sendPhoto(
                        token = token,
                        chatId = chatId,
                        photo = item.photo,
                        caption = item.caption
                    )
                    if (ok) {
                        PendingQueue.remove(this, item)
                    } else {
                        // Hali ham internet yo'q yoki xato — keyingi urinishni kutamiz
                        break
                    }
                }
            }

            flushing = false
            if (!PendingQueue.hasPending(this)) {
                stopSelfSafely()
            }
        }.start()
    }

    private fun stopSelfSafely() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) { /* allaqachon ro'yxatdan o'chirilgan bo'lishi mumkin */ }
        networkCallback = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) { }
        networkCallback = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navbatdagi suratlar",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Internet qaytganda suratlarni yuborish" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Internet kutilmoqda, navbatdagi surat yuboriladi...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
}
