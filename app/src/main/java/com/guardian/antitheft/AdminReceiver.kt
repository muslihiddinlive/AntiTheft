package com.guardian.antitheft

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

/**
 * Device Admin Receiver — qulflangan ekranda parol xato kiritilganda ishga tushadi.
 * Har bir xato urinishda CameraService ni ishga tushiradi.
 * Shuningdek, kimdir Device Admin'ni o'chirib, ilovani o'chirishga urinsa ham xabar beradi.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        triggerCapture(context)
    }

    /** Kimdir Device Admin huquqini o'chirib, ilovani deinstall qilishga uringanda chaqiriladi */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val prefs = context.getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()

        if (token.isNotEmpty() && chatId.isNotEmpty()) {
            Thread {
                TelegramSender.sendMessage(
                    token, chatId,
                    "🚨 OGOHLANTIRISH: Kimdir AntiTheft himoyasini O'CHIRISHGA urinmoqda!"
                )
            }.start()
        }
        triggerCapture(context)
        return "AntiTheft himoyasini o'chirsangiz, qurilma endi kuzatilmaydi. Rostdan ham davom etasizmi?"
    }

    private fun triggerCapture(context: Context) {
        val prefs = context.getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
        val token  = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id",   "").orEmpty()

        // Faqat sozlamalar to'liq bo'lganda ishga tushir
        if (token.isNotEmpty() && chatId.isNotEmpty()) {
            context.startForegroundService(Intent(context, CameraService::class.java))

            if (prefs.getBoolean("dual_camera", true)) {
                context.startForegroundService(
                    Intent(context, CameraService::class.java)
                        .putExtra("lens_facing", "back")
                )
            }
            if (prefs.getBoolean("record_video", true)) {
                context.startForegroundService(Intent(context, VideoCaptureService::class.java))
            }
        }
    }
}
