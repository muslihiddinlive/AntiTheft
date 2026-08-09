package com.guardian.antitheft

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

object DeviceStatusHelper {

    fun getStatusText(context: Context): String {
        val network = getNetworkText(context)
        val battery = getBatteryText(context)
        return "🌐 Tarmoq: $network\n🔋 Batareya: $battery"
    }

    private fun getNetworkText(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Ulanmagan"
            val caps = cm.getNetworkCapabilities(network) ?: return "Ulanmagan"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil internet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Ulangan (noma'lum turi)"
            }
        } catch (e: Exception) {
            "noma'lum"
        }
    }

    private fun getBatteryText(context: Context): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) "${(level * 100 / scale)}%" else "noma'lum"
        } catch (e: Exception) {
            "noma'lum"
        }
    }
}
