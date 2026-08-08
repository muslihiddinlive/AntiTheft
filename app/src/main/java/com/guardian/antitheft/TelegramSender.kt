package com.guardian.antitheft

import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rasmni to'g'ridan-to'g'ri Telegram Bot API orqali yuboradi.
 * Tashqi kutubxona ishlatilmagan — faqat standart Java HttpURLConnection.
 */
object TelegramSender {

    private const val TIMEOUT_MS = 20_000

    /**
     * @param token   Bot tokeni (@BotFather dan olingan)
     * @param chatId  Sizning Telegram Chat ID'ingiz
     * @param photo   JPEG baytlari
     * @param caption Rasm ostidagi matn
     * @return true — muvaffaqiyatli, false — xato
     */
    fun sendPhoto(
        token: String,
        chatId: String,
        photo: ByteArray,
        caption: String = ""
    ): Boolean {
        val boundary = "===Boundary${System.currentTimeMillis()}==="
        val apiUrl   = "https://api.telegram.org/bot$token/sendPhoto"

        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput      = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                // chat_id
                out.writeField(boundary, "chat_id", chatId)

                // caption
                val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date())
                out.writeField(boundary, "caption", "$caption\n🕐 $time")

                // photo (ikkilik fayl)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; " +
                    "name=\"photo\"; filename=\"intruder.jpg\"\r\n"
                )
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                out.write(photo)
                out.writeBytes("\r\n")

                // tugatuvchi boundary
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Faqat matn xabari yuboradi (test uchun)
     */
    fun sendMessage(token: String, chatId: String, text: String): Boolean {
        return try {
            val conn = (URL("https://api.telegram.org/bot$token/sendMessage")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val body = """{"chat_id":"$chatId","text":"$text"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Multipart field yozuvchi yordamchi */
    private fun DataOutputStream.writeField(boundary: String, name: String, value: String) {
        writeBytes("--$boundary\r\n")
        writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        write(value.toByteArray(Charsets.UTF_8))
        writeBytes("\r\n")
    }
}
