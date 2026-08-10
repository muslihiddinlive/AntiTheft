package com.guardian.antitheft

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs    = getSharedPreferences("antitheft_prefs", MODE_PRIVATE)
        val etToken  = findViewById<EditText>(R.id.et_token)
        val etChatId = findViewById<EditText>(R.id.et_chat_id)
        val swGallery    = findViewById<Switch>(R.id.switch_gallery)
        val swAutoResend = findViewById<Switch>(R.id.switch_auto_resend)
        val swDualCamera = findViewById<Switch>(R.id.switch_dual_camera)
        val swRecordVideo = findViewById<Switch>(R.id.switch_record_video)
        val swHideIcon   = findViewById<Switch>(R.id.switch_hide_icon)
        val swRemoteCommands = findViewById<Switch>(R.id.switch_remote_commands)
        val btnSave  = findViewById<Button>(R.id.btn_save)
        val btnTest  = findViewById<Button>(R.id.btn_test)

        // Avvalgi qiymatlarni yuklash
        etToken.setText(prefs.getString("bot_token", ""))
        etChatId.setText(prefs.getString("chat_id", ""))
        swGallery.isChecked     = prefs.getBoolean("save_to_gallery", true)
        swAutoResend.isChecked  = prefs.getBoolean("auto_resend", true)
        swDualCamera.isChecked  = prefs.getBoolean("dual_camera", true)
        swRecordVideo.isChecked = prefs.getBoolean("record_video", true)
        swHideIcon.isChecked    = IconVisibility.isHidden(this)
        swRemoteCommands.isChecked = prefs.getBoolean("remote_commands", false)

        // Ikonkani darhol yashirish/ko'rsatish
        swHideIcon.setOnCheckedChangeListener { _, isChecked ->
            IconVisibility.setHidden(this, isChecked)
            if (isChecked) {
                toast("Ikonka yashirildi. Qaytarish uchun terish ekranida *#*#8228#*#* tering")
            }
        }

        // Saqlash
        btnSave.setOnClickListener {
            val token  = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                toast("Barcha maydonlarni to'ldiring")
                return@setOnClickListener
            }

            prefs.edit()
                .putString("bot_token", token)
                .putString("chat_id",   chatId)
                .putBoolean("save_to_gallery", swGallery.isChecked)
                .putBoolean("auto_resend", swAutoResend.isChecked)
                .putBoolean("dual_camera", swDualCamera.isChecked)
                .putBoolean("record_video", swRecordVideo.isChecked)
                .putBoolean("remote_commands", swRemoteCommands.isChecked)
                .apply()

            if (swRemoteCommands.isChecked) {
                BotCommandListener.start(this)
            }

            toast("✅ Saqlandi")
            finish()
        }

        // Test xabari
        btnTest.setOnClickListener {
            val token  = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                toast("Avval to'ldiring va saqlang")
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            btnTest.text = "Yuborilmoqda..."

            Thread {
                val ok = TelegramSender.sendMessage(
                    token  = token,
                    chatId = chatId,
                    text   = "✅ AntiTheft Guard test: Himoya sozlangan va ishlayapti!"
                )
                runOnUiThread {
                    btnTest.isEnabled = true
                    btnTest.text = "📤 Test xabari yuborish"
                    if (ok) toast("✅ Bot ishlayapti! Telegram'ni tekshiring")
                    else    toast("❌ Xato — token yoki Chat ID noto'g'ri")
                }
            }.start()
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
