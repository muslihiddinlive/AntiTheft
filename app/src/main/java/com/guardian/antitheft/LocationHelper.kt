package com.guardian.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

object LocationHelper {

    /** Ruxsat bo'lsa, oxirgi ma'lum joylashuvni Google Maps havolasi shaklida qaytaradi */
    fun getLastLocationText(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "📍 Lokatsiya: ruxsat berilmagan"
        }

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (provider in providers) {
                val loc = try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
                if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) {
                    best = loc
                }
            }
            if (best != null) {
                "📍 Lokatsiya: https://maps.google.com/?q=${best.latitude},${best.longitude}"
            } else {
                "📍 Lokatsiya: hozircha noma'lum (GPS signal yo'q)"
            }
        } catch (e: Exception) {
            "📍 Lokatsiya: xato (${e.message})"
        }
    }
}
