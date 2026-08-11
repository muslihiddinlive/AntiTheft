package com.guardian.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocationHelper {

    private const val FRESH_FIX_TIMEOUT_SEC = 8L

    // LocationTrackingService yozgan cache 10 daqiqagacha yangi hisoblanadi
    private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L

    /**
     * Ruxsat bo'lsa, joylashuvni Google Maps havolasi shaklida qaytaradi.
     * Avval LocationTrackingService'ning tezkor cache'ini tekshiradi,
     * yo'q bo'lsa yangi GPS fix so'raydi.
     * Chaqiruvchi background thread'da bo'lishi shart (bloklovchi funksiya).
     */
    fun getLastLocationText(context: Context): String {
        if (!hasPermission(context)) return "📍 Lokatsiya: ruxsat berilmagan"

        // 1) Tezkor yo'l: LocationTrackingService'ning SharedPrefs cache'i
        val prefs = context.getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val lat   = prefs.getString("cached_lat", null)?.toDoubleOrNull()
        val lng   = prefs.getString("cached_lng", null)?.toDoubleOrNull()
        val ts    = prefs.getLong("cached_loc_ts", 0L)
        if (lat != null && lng != null && System.currentTimeMillis() - ts < CACHE_MAX_AGE_MS) {
            val ageMin = (System.currentTimeMillis() - ts) / 60_000
            return "📍 Lokatsiya: https://maps.google.com/?q=$lat,$lng (${ageMin}d oldin)"
        }

        // 2) Cache yo'q yoki eskirgan — yangi GPS fix so'raymiz
        val fresh = requestFreshLocation(context)
        if (fresh != null) {
            return "📍 Lokatsiya: https://maps.google.com/?q=${fresh.latitude},${fresh.longitude}"
        }

        val cached = getBestCachedLocation(context)
        return if (cached != null) {
            "📍 Lokatsiya: https://maps.google.com/?q=${cached.latitude},${cached.longitude} (eski)"
        } else {
            "📍 Lokatsiya: hozircha noma'lum (GPS signal yo'q)"
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Bir necha soniya kutib, yangi GPS/tarmoq lokatsiyasini so'raydi */
    private fun requestFreshLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) return null

        val thread = HandlerThread("LocationFix").apply { start() }
        val latch = CountDownLatch(1)
        var result: Location? = null

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            for (provider in providers) {
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, thread.looper)
                } catch (e: SecurityException) { /* ruxsat yo'q */ }
            }
            latch.await(FRESH_FIX_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { lm.removeUpdates(listener) } catch (e: Exception) { }
            thread.quitSafely()
        }

        return result
    }

    private fun getBestCachedLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (provider in lm.getProviders(true)) {
                val loc = try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
                if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) best = loc
            }
            best
        } catch (e: Exception) { null }
    }
}
