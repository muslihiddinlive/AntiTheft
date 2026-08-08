package com.guardian.antitheft

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

/**
 * Device Admin Receiver — qulflangan ekranda parol xato kiritilganda ishga tushadi.
 * Har bir xato urinishda CameraService ni ishga tushiradi.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val prefs = context.getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()

        // Faqat sozlamalar to'liq bo'lganda ishga tushir
        if (token.isNotEmpty() && chatId.isNotEmpty()) {
            val serviceIntent = Intent(context, CameraService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
