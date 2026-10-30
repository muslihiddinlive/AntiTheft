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
     * Video yuboradi (masalan qisqa "intruder" clip)
     */
    fun sendVideo(
        token: String,
        chatId: String,
        video: ByteArray,
        caption: String = ""
    ): Boolean {
        val boundary = "===Boundary${System.currentTimeMillis()}==="
        val apiUrl   = "https://api.telegram.org/bot$token/sendVideo"

        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput      = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = 120_000          // 2 daqiqa
                setChunkedStreamingMode(4096)     // exact size bilmasdan upload
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.writeField(boundary, "chat_id", chatId)
                out.writeField(boundary, "caption", caption)

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; " +
                    "name=\"video\"; filename=\"intruder.mp4\"\r\n"
                )
                out.writeBytes("Content-Type: video/mp4\r\n\r\n")
                out.write(video)
                out.writeBytes("\r\n")
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
     * Audio/ovoz yozuvini yuboradi (masalan yon tugma orqali yozilgan qisqa klip)
     */
    fun sendAudio(
        token: String,
        chatId: String,
        audio: ByteArray,
        caption: String = ""
    ): Boolean {
        val boundary = "===Boundary${System.currentTimeMillis()}==="
        val apiUrl   = "https://api.telegram.org/bot$token/sendAudio"

        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput      = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = 30_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.writeField(boundary, "chat_id", chatId)
                out.writeField(boundary, "caption", caption)

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; " +
                    "name=\"audio\"; filename=\"voice.m4a\"\r\n"
                )
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                out.write(audio)
                out.writeBytes("\r\n")
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

    /** JSON yoki boshqa hujjatni Telegram'ga yuboradi (zaxira nusxa uchun) */
    fun sendDocument(
        token: String,
        chatId: String,
        data: ByteArray,
        filename: String,
        caption: String = ""
    ): Boolean {
        return try {
            val boundary = "antitheft_doc_${System.currentTimeMillis()}"
            val conn = (URL("https://api.telegram.org/bot$token/sendDocument")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeField(boundary, "chat_id", chatId)
                if (caption.isNotEmpty()) dos.writeField(boundary, "caption", caption)
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"$filename\"\r\n")
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                dos.write(data)
                dos.writeBytes("\r\n--$boundary--\r\n")
            }
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
