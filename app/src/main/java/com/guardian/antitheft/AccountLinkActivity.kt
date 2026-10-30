package com.guardian.antitheft

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * Backup uchun 1 dan 5 tagacha Google akkauntni ulash/uzish ekrani.
 *
 * Jarayon:
 *  1. Qurilmadagi Google akkauntlar ro'yxati ko'rsatiladi
 *  2. Foydalanuvchi "Ulash" bosadi → Android'ning Drive ruxsat dialogi chiqadi
 *  3. Foydalanuvchi ruxsat bersa → akkaunt ulanadi, BackupHelper'ga saqlanadi
 *  4. Keyingi /backup da bu akkauntning Drive'iga fayllar yuklanadi
 *
 * Ulangan akkauntlar SecurePrefs emas, oddiy backup_prefs'da saqlanadi
 * (token emas, faqat email — token AccountManager tomonidan boshqariladi).
 */
class AccountLinkActivity : AppCompatActivity() {

    private val linked = mutableListOf<String>()
    private val all    = mutableListOf<String>()
    private var pendingEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_link)
        title = "Google Akkauntlarni ulash"

        linked.addAll(BackupHelper.getLinkedAccounts(this))
        all.addAll(AccountManager.get(this).getAccountsByType("com.google").map { it.name })

        if (all.isEmpty()) {
            Toast.makeText(this, "Qurilmada Google akkaunt topilmadi", Toast.LENGTH_LONG).show()
            finish(); return
        }
        render()
    }

    private fun render() {
        val tv = findViewById<TextView>(R.id.tv_info)
        val lv = findViewById<ListView>(R.id.lv_accounts)

        tv.text = "Ulangan: ${linked.size}/5  •  Akkauntga bosib ulang yoki aloqani uzing"

        val labels = all.map { email ->
            if (email in linked) "✅  $email" else "➕  $email"
        }
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        lv.setOnItemClickListener { _, _, pos, _ ->
            val email = all[pos]
            if (email in linked) {
                linked.remove(email)
                BackupHelper.saveLinkedAccounts(this, linked)
                Toast.makeText(this, "🔌 $email aloqasi uzildi", Toast.LENGTH_SHORT).show()
                render()
            } else {
                if (linked.size >= 5) {
                    Toast.makeText(this, "Maksimal 5 ta akkaunt ulanishi mumkin", Toast.LENGTH_SHORT).show()
                } else {
                    requestAuth(email)
                }
            }
        }
    }

    private fun requestAuth(email: String) {
        pendingEmail = email
        val account = AccountManager.get(this).getAccountsByType("com.google")
            .firstOrNull { it.name == email } ?: return

        AccountManager.get(this).getAuthToken(
            account,
            "oauth2:https://www.googleapis.com/auth/drive.file",
            null,
            this,               // Activity → ruxsat dialogi ko'rsatiladi
            { future ->
                runOnUiThread {
                    try {
                        val bundle = future.result
                        @Suppress("DEPRECATION")
                        val intent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
                        if (intent != null) {
                            // Android qo'shimcha tasdiqlash talab qilmoqda
                            @Suppress("DEPRECATION")
                            startActivityForResult(intent, REQ_AUTH)
                        } else {
                            val tok = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                            if (tok != null) {
                                // Darhol bekor qilamiz — faqat ruxsat kerak edi,
                                // token BackupJob paytida yangilanadi
                                AccountManager.get(this).invalidateAuthToken("com.google", tok)
                                onLinked(email)
                            } else {
                                Toast.makeText(this, "Token olinmadi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }, null
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) {
            pendingEmail?.let { requestAuth(it) }   // ruxsat berildi, qayta urinib ko'ramiz
        }
    }

    private fun onLinked(email: String) {
        if (email !in linked) linked.add(email)
        BackupHelper.saveLinkedAccounts(this, linked)
        Toast.makeText(this, "✅ $email ulandi", Toast.LENGTH_SHORT).show()
        render()
    }

    companion object { private const val REQ_AUTH = 91 }
}
