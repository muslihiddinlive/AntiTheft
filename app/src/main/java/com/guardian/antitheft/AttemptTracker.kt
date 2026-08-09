package com.guardian.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object AttemptTracker {

    private const val PREFS = "antitheft_prefs"
    private const val KEY_COUNT = "attempt_count"
    private const val KEY_LAST_TS = "attempt_last_ts"
    private const val KEY_SIM_ID = "known_sim_id"
    // Shu vaqt oralig'ida ketma-ket kelgan urinishlar "seriya" deb hisoblanadi
    private const val SERIES_WINDOW_MS = 5 * 60 * 1000L

    /** Yangi noto'g'ri urinishni qayd qiladi va tavsif matnini qaytaradi */
    fun recordAttempt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastTs = prefs.getLong(KEY_LAST_TS, 0)
        val prevCount = prefs.getInt(KEY_COUNT, 0)

        val count = if (now - lastTs <= SERIES_WINDOW_MS) prevCount + 1 else 1

        prefs.edit()
            .putInt(KEY_COUNT, count)
            .putLong(KEY_LAST_TS, now)
            .apply()

        return if (count >= 3) {
            "🚨 XAVFLI! Ketma-ket $count marta noto'g'ri parol urinishi!"
        } else {
            "⚠️ Noto'g'ri parol urinishi ($count-marta)"
        }
    }

    /** SIM karta almashtirilganmi tekshiradi; almashtirilgan bo'lsa xabar matnini qaytaradi, aks holda null */
    fun checkSimChange(context: Context): String? {
        val simId = getCurrentSimId(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val knownId = prefs.getString(KEY_SIM_ID, null)

        if (knownId == null) {
            // Birinchi marta — shuni "normal" SIM sifatida saqlab qo'yamiz
            prefs.edit().putString(KEY_SIM_ID, simId).apply()
            return null
        }

        if (knownId != simId) {
            prefs.edit().putString(KEY_SIM_ID, simId).apply()
            return "🚨 SIM KARTA ALMASHTIRILDI!"
        }
        return null
    }

    @Suppress("MissingPermission")
    private fun getCurrentSimId(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager
            val list = sm.activeSubscriptionInfoList ?: return null
            list.joinToString("|") { it.iccId ?: it.subscriptionId.toString() }
                .ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
