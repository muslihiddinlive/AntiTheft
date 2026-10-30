package com.guardian.antitheft

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub'dagi version.json ni o'qib, joriy versiya bilan solishtirib,
 * yangi versiya bo'lsa yuklab o'rnatadi.
 *
 * version.json shakli:
 *   { "versionCode": 5, "versionName": "1.4",
 *     "apkUrl": "https://raw.githubusercontent.com/muslihiddinlive/AntiTheft/main/releases/AntiTheft-debug.apk",
 *     "changelog": "Nima yangi" }
 */
object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/muslihiddinlive/AntiTheft/feature/screenrecord/version.json"

    /** Ilovani ochganda yoki tugma bosilganda chaqiriladi. silent=true da faqat yangilanish bor bo'lsa dialog ko'rsatadi */
    fun check(context: Context, silent: Boolean = true) {
        Thread {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout    = 6_000
                conn.connect()
                if (conn.responseCode != 200) {
                    if (!silent) showToast(context, "Versiya ma'lumoti olinmadi")
                    return@Thread
                }
                val json    = conn.inputStream.bufferedReader().readText()
                val obj     = JSONObject(json)
                val remCode = obj.getInt("versionCode")
                val remName = obj.getString("versionName")
                val apkUrl  = obj.getString("apkUrl")
                val change  = obj.optString("changelog", "")

                val curCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionCode

                if (remCode > curCode) {
                    uiThread { showUpdateDialog(context, remName, change, apkUrl) }
                } else if (!silent) {
                    uiThread { showToast(context, "✅ Ilova eng yangi versiyada ($remName)") }
                }
            } catch (e: Exception) {
                if (!silent) uiThread { showToast(context, "Yangilanishni tekshirib bo'lmadi") }
            }
        }.start()
    }

    // ── Dialog ───────────────────────────────────────────────────────

    private fun showUpdateDialog(context: Context, version: String, changelog: String, url: String) {
        val msg = buildString {
            append("Yangi versiya $version tayyor.")
            if (changelog.isNotEmpty()) append("\n\nNima yangi:\n$changelog")
        }
        AlertDialog.Builder(context)
            .setTitle("🔄 Yangilanish mavjud")
            .setMessage(msg)
            .setPositiveButton("Yuklab o'rnatish") { _, _ -> downloadAndInstall(context, url) }
            .setNegativeButton("Keyinroq", null)
            .setCancelable(true)
            .show()
    }

    // ── Yuklab olish va o'rnatish ────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun downloadAndInstall(context: Context, apkUrl: String) {
        val progress = ProgressDialog(context).apply {
            setTitle("Yuklanmoqda...")
            setMessage("Yangi versiya yuklanmoqda, iltimos kuting...")
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val dir  = File(context.filesDir, "updates").also { it.mkdirs() }
                val apk  = File(dir, "update.apk")

                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout    = 60_000
                conn.connect()
                val total  = conn.contentLength
                val input  = conn.inputStream
                val output = apk.outputStream()
                val buf    = ByteArray(8192)
                var downloaded = 0
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val pct = (downloaded * 100 / total)
                        uiThread { progress.progress = pct }
                    }
                }
                output.close()
                input.close()

                uiThread {
                    progress.dismiss()
                    installApk(context, apk)
                }
            } catch (e: Exception) {
                uiThread {
                    progress.dismiss()
                    showToast(context, "Yuklab bo'lmadi: ${e.message}")
                }
            }
        }.start()
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // ── Yordamchilar ────────────────────────────────────────────────

    private fun uiThread(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    private fun showToast(context: Context, msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
