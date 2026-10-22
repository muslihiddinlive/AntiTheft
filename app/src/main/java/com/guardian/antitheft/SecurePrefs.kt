package com.guardian.antitheft

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * bot_token va chat_id kabi sezgir qiymatlarni shifrlab saqlash uchun markazlashtirilgan
 * SharedPreferences provayderi (Android Keystore asosidagi AES256-GCM).
 *
 * Avvalgi kodda har bir fayl o'zi to'g'ridan-to'g'ri
 * `getSharedPreferences("antitheft_prefs", MODE_PRIVATE)` chaqirar edi — bu qiymatlarni
 * oddiy matn holida /data/data/.../shared_prefs/antitheft_prefs.xml fayliga yozar edi.
 * Root olingan yoki backup orqali o'qilgan qurilmada token/chat_id osongina o'qilishi mumkin edi.
 *
 * Shu obyekt orqali barcha joylarda bir xil fayl nomi ("antitheft_prefs") ostida,
 * lekin endi shifrlangan holda saqlanadi — chaqiruv joylarining imzosi deyarli o'zgarmaydi,
 * faqat `context.getSharedPreferences("antitheft_prefs", MODE_PRIVATE)` o'rniga
 * `SecurePrefs.get(context)` deb chaqiriladi.
 */
object SecurePrefs {

    private const val PREFS_FILE = "antitheft_prefs"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }
            val prefs = try {
                createEncrypted(context)
            } catch (e: Exception) {
                // Keystore ba'zi qurilmalarda (masalan, zaxiradan tiklangandan keyin)
                // eski kalit bilan mos kelmay qolishi mumkin. Bunday holatda eski shifrlangan
                // faylni o'chirib, kalitni qaytadan yaratamiz — token/chat_id yo'qoladi,
                // lekin ilova MUTLAQO ishdan to'xtamaydi (crash-loop bo'lmaydi).
                Log.w(TAG, "EncryptedSharedPreferences ochilmadi, qayta yaratilmoqda", e)
                context.deleteSharedPreferences(PREFS_FILE)
                createEncrypted(context)
            }
            cached = prefs
            return prefs
        }
    }

    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
