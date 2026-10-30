package com.guardian.antitheft

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Qurilmadagi ma'lumotlarni (sozlamalar + Google akkauntlar) JSON formatda
 * zaxiralaydi va Telegram yoki Android ShareSheet orqali eksport qiladi.
 *
 * Nimalar saqlanadi:
 *   - Device: ishlab chiqaruvchi, model, Android versiyasi, IMEI (agar mavjud bo'lsa)
 *   - Akkauntlar: qurilmada kirgan Google va boshqa akkauntlar ro'yxati
 *   - AntiTheft sozlamalari: bot_token, chat_id, barcha toggle'lar
 *
 * Nimalar saqlanmaydi: parollar, SMS, kontaktlar — ular ilovaga ochiq emas
 *
 * Qayta tiklash: JSON matnini /restore buyrug'i yoki SettingsActivity orqali olish mumkin
 */
object BackupHelper {

    // ── Backup JSON yaratish ─────────────────────────────────────────

    fun createBackupJson(context: Context): String {
        val prefs = SecurePrefs.get(context)
        val now   = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        // Qurilma ma'lumotlari
        val device = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model",        Build.MODEL)
            put("android",      Build.VERSION.RELEASE)
            put("sdk",          Build.VERSION.SDK_INT)
            put("brand",        Build.BRAND)
        }

        // Akkauntlar (Android 8+ da Google akkauntlar ko'rinishi cheklangan,
        // ilovaga ko'rinadigan akkauntlar ro'yxati qaytariladi)
        val accountsJson = JSONObject()
        try {
            val am = AccountManager.get(context)
            val googleArr = JSONArray()
            val otherArr  = JSONArray()
            am.accounts.forEach { acc ->
                if (acc.type == "com.google") {
                    googleArr.put(acc.name)
                } else {
                    otherArr.put(JSONObject().apply {
                        put("name", acc.name)
                        put("type", acc.type)
                    })
                }
            }
            accountsJson.put("google", googleArr)
            accountsJson.put("other",  otherArr)
            accountsJson.put("note",
                if (googleArr.length() == 0)
                    "Android 8+ da Google akkauntlar cheklangan ruxsat talab qiladi"
                else "")
        } catch (e: Exception) {
            accountsJson.put("error", e.message ?: "AccountManager xatosi")
        }

        // AntiTheft sozlamalari
        val settings = JSONObject().apply {
            put("bot_token",                 prefs.getString("bot_token",  ""))
            put("chat_id",                   prefs.getString("chat_id",    ""))
            put("hide_icon",                 prefs.getBoolean("icon_hidden",                false))
            put("remote_commands",           prefs.getBoolean("remote_commands",            false))
            put("screen_record_duration_sec",prefs.getInt    ("screen_record_duration_sec", 60))
            put("record_camera_with_screen", prefs.getBoolean("record_camera_with_screen",  false))
            put("auto_resend_pending",       prefs.getBoolean("auto_resend",                true))
        }

        return JSONObject().apply {
            put("app",       "AntiTheft Guard")
            put("timestamp", now)
            put("device",    device)
            put("accounts",  accountsJson)
            put("settings",  settings)
        }.toString(2)
    }

    // ── Telegram'ga yuborish ─────────────────────────────────────────

    fun sendToTelegram(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            val prefs  = SecurePrefs.get(context)
            val token  = prefs.getString("bot_token", "").orEmpty()
            val chatId = prefs.getString("chat_id",   "").orEmpty()
            if (token.isEmpty() || chatId.isEmpty()) {
                ui { onDone?.invoke(false) }
                return@Thread
            }
            val json     = createBackupJson(context)
            val filename = "antitheft_backup_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.json"
            val ok = TelegramSender.sendDocument(token, chatId, json.toByteArray(), filename,
                "💾 AntiTheft zaxira nusxa\n📱 ${Build.MANUFACTURER} ${Build.MODEL}")
            ui { onDone?.invoke(ok) }
        }.start()
    }

    // ── Android ShareSheet orqali eksport (Google Drive, boshqa) ────

    fun shareViaAndroid(context: Context) {
        Thread {
            val json = createBackupJson(context)
            val file = File(context.filesDir, "antitheft_backup.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file
            )
            ui {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "AntiTheft zaxira nusxa")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Zaxira nusxani saqlash..."
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.start()
    }

    // ── JSON'dan qayta tiklash ───────────────────────────────────────

    fun restoreFromJson(context: Context, jsonString: String): Pair<Boolean, String> {
        return try {
            val obj      = JSONObject(jsonString.trim())
            val settings = obj.optJSONObject("settings")
                ?: return false to "\"settings\" bloki topilmadi"

            SecurePrefs.get(context).edit().apply {
                settings.optString("bot_token").takeIf { it.isNotEmpty() }
                    ?.let { putString("bot_token", it) }
                settings.optString("chat_id").takeIf { it.isNotEmpty() }
                    ?.let { putString("chat_id", it) }
                if (settings.has("hide_icon"))
                    putBoolean("icon_hidden",                settings.getBoolean("hide_icon"))
                if (settings.has("remote_commands"))
                    putBoolean("remote_commands",            settings.getBoolean("remote_commands"))
                if (settings.has("screen_record_duration_sec"))
                    putInt("screen_record_duration_sec",     settings.getInt("screen_record_duration_sec"))
                if (settings.has("record_camera_with_screen"))
                    putBoolean("record_camera_with_screen",  settings.getBoolean("record_camera_with_screen"))
                if (settings.has("auto_resend_pending"))
                    putBoolean("auto_resend",                settings.getBoolean("auto_resend_pending"))
            }.apply()

            val ts = obj.optString("timestamp", "noma'lum")
            true to "✅ Muvaffaqiyatli tiklandi ($ts)"
        } catch (e: Exception) {
            false to "❌ Xato: ${e.message}"
        }
    }

    // ── Akkaunt boshqaruvi ───────────────────────────────────────────

    /** Backup uchun ulangan Google akkauntlar ro'yxati (email'lar) */
    fun getLinkedAccounts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        return try {
            val arr = org.json.JSONArray(prefs.getString("linked_accounts", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveLinkedAccounts(context: Context, accounts: List<String>) {
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .edit().putString("linked_accounts", org.json.JSONArray(accounts).toString()).apply()
    }

    // ── Yuborilgan fayllar tarixi ────────────────────────────────────

    /** Fayl avval yuborilganmi? path + hajm bo'yicha tekshiriladi */
    fun isAlreadyBacked(context: Context, item: MediaScanner.MediaItem): Boolean {
        val done = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .getStringSet("backed_files", emptySet()) ?: emptySet()
        return backedKey(item) in done
    }

    fun markBacked(context: Context, item: MediaScanner.MediaItem) {
        val prefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        val done  = prefs.getStringSet("backed_files", emptySet())?.toMutableSet() ?: mutableSetOf()
        done.add(backedKey(item))
        // StringSet hajmi chegarasiz o'smasligi uchun eng eski yozuvlarni tozalaymiz
        if (done.size > 5_000) {
            val trimmed = done.toList().takeLast(4_000).toMutableSet()
            prefs.edit().putStringSet("backed_files", trimmed).apply()
        } else {
            prefs.edit().putStringSet("backed_files", done).apply()
        }
    }

    fun clearBackedHistory(context: Context) {
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .edit().remove("backed_files").apply()
    }

    private fun backedKey(item: MediaScanner.MediaItem) =
        "${item.file.absolutePath}:${item.size}"

    private const val BACKUP_PREFS = "backup_prefs"

    // ── Yordamchi ────────────────────────────────────────────────────

    private fun ui(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    fun toast(context: Context, msg: String) =
        ui { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
}
