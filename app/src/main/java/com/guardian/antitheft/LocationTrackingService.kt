package com.guardian.antitheft

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Joylashuvni doim kuzatib boruvchi foreground xizmat.
 *
 * Nima qiladi:
 *  - GPS/tarmoq provayderidan har [UPDATE_INTERVAL_MS] da yangi lokatsiya oladi
 *  - Oxirgi ma'lum joylashuvni SharedPreferences'ga yozadi (cached_lat, cached_lng)
 *  - LocationHelper bu cache'ni o'qiydi → trigger bo'lganda joylashuv tezda tayyor bo'ladi,
 *    GPS fix uchun kutish shart emas
 *
 * MIUI/HyperOS eslatmasi:
 *  Xizmat BOOT_COMPLETED da avtomatik ishga tushiriladi.
 *  Lekin Redmi/Xiaomi qurilmalarida "Autostart" va "No restrictions"
 *  (Batareya sozlamalari) qo'lda yoqilmasa, qurilma sleep'ga kirganida
 *  xizmat o'ldirilishi mumkin. Bu Android tizimi cheklovi, kod darajasida
 *  to'liq kafolatlab bo'lmaydi.
 */
class LocationTrackingService : Service() {

    private var locationManager: LocationManager? = null
    private var trackerThread: HandlerThread? = null

    companion object {
        private const val CHANNEL_ID   = "antitheft_location_channel"
        private const val NOTIF_ID     = 47
        // Har 3 daqiqada bir marta yangilaydi — GPS batareyasini tejash bilan muvozanat
        private const val UPDATE_INTERVAL_MS = 3 * 60 * 1000L
        // Eng kamida 30 metr o'zgarsa yangilaydi
        private const val UPDATE_MIN_DISTANCE_M = 30f

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, LocationTrackingService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            saveToCache(location)
        }

        override fun onProviderEnabled(provider: String)  {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startTracking()
        return START_STICKY   // tizim o'ldirgandan keyin qayta tiklaydi
    }

    private fun startTracking() {
        val lm = locationManager ?: return

        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val thread = HandlerThread("LocationTracker").apply { start() }
        trackerThread = thread

        for (provider in lm.getProviders(true)) {
            try {
                lm.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MS,
                    UPDATE_MIN_DISTANCE_M,
                    locationListener,
                    thread.looper
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Darhol cached lokatsiyani ham yozib qo'yamiz (fresh fix kelgunga qadar)
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
            } catch (e: Exception) { }
        }
        best?.let { saveToCache(it) }
    }

    private fun saveToCache(location: Location) {
        getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("cached_lat",  location.latitude.toString())
            .putString("cached_lng",  location.longitude.toString())
            .putLong("cached_loc_ts", System.currentTimeMillis())
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) {}
        trackerThread?.quitSafely()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Joylashuv kuzatuv", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description    = "Joylashuvni doim kuzatib boradi"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Guard")
            .setContentText("Himoya faol")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
}
