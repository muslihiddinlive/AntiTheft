package com.guardian.antitheft

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Shaffof Activity — foydalanuvchi uni ko'rmaydi, faqat tizimning o'z
 * "Screen recording ruxsati" dialogini ochadi (FuizRec'dagi PermissionActivity
 * namunasi asosida).
 *
 * MUHIM CHEKLOV: bu — Android platformasining o'zi talab qiladigan dialog.
 * /screenrecord buyrug'i qurilmaga yetib borganda, qurilma egasi ekranda
 * chiqqan "Hozir boshlash" tugmasini albatta bosishi kerak — buni dastur
 * ichidan avtomatik bosib o'tib bo'lmaydi (root bo'lmasa). Shuning uchun bu
 * buyruq, qurilma qulflanmagan va kimdir uni jismonan ushlab turganida
 * (masalan, egasi o'z qurilmasini uzoqdan tekshirmoqchi bo'lsa) ishlaydi —
 * o'g'irlangan va qulflangan qurilmada chaqirilsa, dialog chiqadi-yu,
 * lekin uni bosadigan hech kim bo'lmaydi.
 */
class ScreenRecordPermissionActivity : Activity() {

    private var durationSec = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        durationSec = intent.getIntExtra(ScreenRecordService.EXTRA_DURATION_SEC, 20)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_CODE, resultCode)
                    putExtra(ScreenRecordService.EXTRA_DATA, data)
                    putExtra(ScreenRecordService.EXTRA_DURATION_SEC, durationSec)
                }
            )
        } else {
            val prefs  = SecurePrefs.get(this)
            val token  = prefs.getString("bot_token", "").orEmpty()
            val chatId = prefs.getString("chat_id",   "").orEmpty()
            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                TelegramSender.sendMessage(token, chatId,
                    "⚠️ Ekran yozuvi uchun ruxsat berilmadi (dialog yopildi yoki \"Rad etish\" bosildi).")
            }
        }
        finish()
    }

    companion object { const val REQ = 78 }
}
