package com.guardian.antitheft

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs    = SecurePrefs.get(this)
        val etToken  = findViewById<EditText>(R.id.et_token)
        val etChatId = findViewById<EditText>(R.id.et_chat_id)
        val swGallery    = findViewById<Switch>(R.id.switch_gallery)
        val swAutoResend = findViewById<Switch>(R.id.switch_auto_resend)
        val swDualCamera = findViewById<Switch>(R.id.switch_dual_camera)
        val swRecordVideo = findViewById<Switch>(R.id.switch_record_video)
        val swHideIcon   = findViewById<Switch>(R.id.switch_hide_icon)
        val swRemoteCommands = findViewById<Switch>(R.id.switch_remote_commands)
        val spinnerDuration = findViewById<Spinner>(R.id.spinner_screen_duration)
        val btnSave          = findViewById<Button>(R.id.btn_save)
        val btnTest          = findViewById<Button>(R.id.btn_test)
        val btnBackupTg      = findViewById<Button>(R.id.btn_backup_telegram)
        val btnBackupShare   = findViewById<Button>(R.id.btn_backup_share)
        val btnRestore       = findViewById<Button>(R.id.btn_restore)
        val etRestoreJson    = findViewById<EditText>(R.id.et_restore_json)

        // Google akkauntlarni ulash
        findViewById<Button>(R.id.btn_google_accounts).setOnClickListener {
            startActivity(Intent(this, AccountLinkActivity::class.java))
        }

        // Backup tarixini tozalash
        findViewById<Button>(R.id.btn_clear_backup_history).setOnClickListener {
            BackupHelper.clearBackedHistory(this)
            toast("🔄 Backup tarixi tozalandi — keyingi /backup da barchasi qayta yuboriladi")
        }

        btnBackupTg.setOnClickListener {
            btnBackupTg.isEnabled = false
            btnBackupTg.text = "⏳ Yuborilmoqda..."
            BackupHelper.sendToTelegram(this) { ok ->
                btnBackupTg.isEnabled = true
                btnBackupTg.text = "📨 Telegram'ga yuborish"
                toast(if (ok) "✅ Zaxira nusxa yuborildi" else "❌ Yuborishda xatolik")
            }
        }

        btnBackupShare.setOnClickListener {
            BackupHelper.shareViaAndroid(this)
        }

        btnRestore.setOnClickListener {
            val json = etRestoreJson.text.toString().trim()
            if (json.isEmpty()) { toast("JSON matnini kiriting"); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle("Qayta tiklash")
                .setMessage("Joriy sozlamalar o'zgartiriladi. Davom etasizmi?")
                .setPositiveButton("Ha") { _, _ ->
                    val (ok, msg) = BackupHelper.restoreFromJson(this, json)
                    toast(msg)
                    if (ok) {
                        etRestoreJson.setText("")
                        recreate()
                    }
                }
                .setNegativeButton("Yo'q", null)
                .show()
        }

        // Ekran yozuvi muddati variantlari
        val durationOptions = listOf(
            "30 soniya"  to 30,
            "1 daqiqa"   to 60,
            "5 daqiqa"   to 300,
            "15 daqiqa"  to 900,
            "30 daqiqa"  to 1800,
            "1 soat"     to 3600,
            "2 soat"     to 7200,
            "5 soat"     to 18000
        )
        ArrayAdapter(this, android.R.layout.simple_spinner_item,
            durationOptions.map { it.first }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerDuration.adapter = it
        }

        // Avvalgi qiymatlarni yuklash
        etToken.setText(prefs.getString("bot_token", ""))
        etChatId.setText(prefs.getString("chat_id", ""))
        swGallery.isChecked     = prefs.getBoolean("save_to_gallery", true)
        swAutoResend.isChecked  = prefs.getBoolean("auto_resend", true)
        swDualCamera.isChecked  = prefs.getBoolean("record_camera_with_screen", false)
        swRecordVideo.isChecked = prefs.getBoolean("record_video", true)
        swHideIcon.isChecked    = IconVisibility.isHidden(this)
        swRemoteCommands.isChecked = prefs.getBoolean("remote_commands", false)
        spinnerDuration.setSelection(
            durationOptions.indexOfFirst { it.second == prefs.getInt("screen_record_duration_sec", 60) }
                .coerceAtLeast(0)
        )

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
                .putBoolean("record_camera_with_screen", swDualCamera.isChecked)
                .putBoolean("record_video", swRecordVideo.isChecked)
                .putBoolean("remote_commands", swRemoteCommands.isChecked)
                .putInt("screen_record_duration_sec",
                    durationOptions[spinnerDuration.selectedItemPosition].second)
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
