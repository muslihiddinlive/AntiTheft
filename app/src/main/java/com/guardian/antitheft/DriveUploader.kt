package com.guardian.antitheft

import android.accounts.AccountManager
import android.content.Context
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Drive REST API orqali fayllarni yuklaydi.
 *
 * Fayllar "AntiTheft Backup" nomli papkaga saqlanadi —
 * foydalanuvchi Drive'dan ko'rishi va boshqa qurilmalardan olishi mumkin.
 *
 * <= 5 MB  → oddiy multipart upload
 * >  5 MB  → resumable (chunked) upload — 5 MB lik bo'laklarda yuboriladi,
 *            tarmoq uzilsa xato qaytariladi (retry BackupJob tomonida)
 */
object DriveUploader {

    private const val BASE          = "https://www.googleapis.com"
    private const val UPLOAD_URL    = "$BASE/upload/drive/v3/files"
    private const val FILES_URL     = "$BASE/drive/v3/files"
    private const val ABOUT_URL     = "$BASE/drive/v3/about"
    private const val DRIVE_SCOPE   = "oauth2:https://www.googleapis.com/auth/drive.file"
    private const val FOLDER_NAME   = "AntiTheft Backup"
    private const val CHUNK_SIZE    = 5 * 1024 * 1024          // 5 MB
    private const val SMALL_LIMIT   = 5 * 1024 * 1024L         // 5 MB
    private const val CONNECT_MS    = 30_000
    private const val READ_MS       = 120_000

    data class UploadResult(val ok: Boolean, val error: String = "")

    // ── Token ────────────────────────────────────────────────────────

    /** AccountManager orqali cached Drive token oladi. UI ko'rsatmaydi. */
    fun getToken(context: Context, accountName: String): String? {
        return try {
            val am  = AccountManager.get(context)
            val acc = am.getAccountsByType("com.google").firstOrNull { it.name == accountName }
                ?: return null
            // notifyAuthFailure=false → UI ko'rsatmaydi, cached token qaytaradi
            val bundle = am.getAuthToken(acc, DRIVE_SCOPE, null, false, null, null).result
            bundle.getString(AccountManager.KEY_AUTHTOKEN)
        } catch (e: Exception) { null }
    }

    fun invalidateToken(context: Context, token: String) {
        AccountManager.get(context).invalidateAuthToken("com.google", token)
    }

    // ── Xotira tekshiruvi ────────────────────────────────────────────

    /**
     * Bo'sh xotirani baytda qaytaradi.
     * -1 = xatolik, -2 = token eskirgan (401)
     */
    fun getFreeBytes(token: String): Long {
        return try {
            val conn = get("$ABOUT_URL?fields=storageQuota", token)
            if (conn.responseCode == 401) return -2L
            if (conn.responseCode != 200) return -1L
            val q = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONObject("storageQuota")
            val limit = q.optLong("limit", -1L)
            val usage = q.optLong("usage", 0L)
            if (limit == -1L) Long.MAX_VALUE else (limit - usage)
        } catch (e: Exception) { -1L }
    }

    // ── Papka ────────────────────────────────────────────────────────

    /** "AntiTheft Backup" papkasini topadi yoki yaratadi, ID qaytaradi */
    fun getOrCreateFolder(token: String): String? {
        return try {
            // Mavjudini qidirish
            val q = "name%3D%27$FOLDER_NAME%27+and+mimeType%3D%27application%2Fvnd.google-apps.folder%27+and+trashed%3Dfalse"
            val conn = get("$FILES_URL?q=$q&fields=files(id)", token)
            if (conn.responseCode == 200) {
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("files")
                if (arr.length() > 0) return arr.getJSONObject(0).getString("id")
            }
            // Yaratish
            val cr = post(FILES_URL, token, "application/json",
                """{"name":"$FOLDER_NAME","mimeType":"application/vnd.google-apps.folder"}""".toByteArray())
            if (cr.responseCode in 200..299)
                JSONObject(cr.inputStream.bufferedReader().readText()).getString("id")
            else null
        } catch (e: Exception) { null }
    }

    // ── Yuklash ──────────────────────────────────────────────────────

    fun upload(token: String, file: File, mimeType: String, folderId: String?): UploadResult {
        val meta = buildString {
            append("""{"name":${JSONObject.quote(file.name)}""")
            if (folderId != null) append(""","parents":["$folderId"]""")
            append("}")
        }
        return if (file.length() <= SMALL_LIMIT)
            multipartUpload(token, file, meta, mimeType)
        else
            resumableUpload(token, file, meta, mimeType)
    }

    private fun multipartUpload(token: String, file: File, meta: String, mime: String): UploadResult {
        val b = "at_boundary_${System.currentTimeMillis()}"
        return try {
            val conn = (URL("$UPLOAD_URL?uploadType=multipart").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/related; boundary=$b")
                connectTimeout = CONNECT_MS; readTimeout = READ_MS
            }
            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeBytes("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                dos.write(meta.toByteArray(Charsets.UTF_8))
                dos.writeBytes("\r\n--$b\r\nContent-Type: $mime\r\n\r\n")
                file.inputStream().use { it.copyTo(dos) }
                dos.writeBytes("\r\n--$b--\r\n")
            }
            val code = conn.responseCode
            UploadResult(code in 200..299, if (code !in 200..299) "HTTP $code" else "")
        } catch (e: Exception) { UploadResult(false, e.message ?: "upload error") }
    }

    private fun resumableUpload(token: String, file: File, meta: String, mime: String): UploadResult {
        val size = file.length()
        return try {
            // 1. Boshlash — upload URI olish
            val init = (URL("$UPLOAD_URL?uploadType=resumable").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Upload-Content-Type", mime)
                setRequestProperty("X-Upload-Content-Length", size.toString())
                connectTimeout = CONNECT_MS; readTimeout = CONNECT_MS
            }
            init.outputStream.write(meta.toByteArray(Charsets.UTF_8))
            if (init.responseCode !in 200..299)
                return UploadResult(false, "Initiate: HTTP ${init.responseCode}")
            val uploadUri = init.getHeaderField("Location")
                ?: return UploadResult(false, "No Location header")

            // 2. Bo'laklarda yuklash
            val buf = ByteArray(CHUNK_SIZE)
            var offset = 0L
            file.inputStream().use { fis ->
                while (offset < size) {
                    val read = fis.read(buf)
                    if (read == -1) break
                    val end = offset + read - 1
                    val chunk = (URL(uploadUri).openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"; doOutput = true
                        setRequestProperty("Content-Length", read.toString())
                        setRequestProperty("Content-Range", "bytes $offset-$end/$size")
                        connectTimeout = CONNECT_MS; readTimeout = READ_MS
                    }
                    chunk.outputStream.write(buf, 0, read)
                    val code = chunk.responseCode
                    // 308 = Resume Incomplete (davom etish), 200/201 = tugadi
                    if (code !in 200..299 && code != 308)
                        return UploadResult(false, "Chunk $offset: HTTP $code")
                    offset += read
                }
            }
            UploadResult(true)
        } catch (e: Exception) { UploadResult(false, e.message ?: "resumable error") }
    }

    // ── HTTP yordamchilar ────────────────────────────────────────────

    private fun get(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = CONNECT_MS; readTimeout = CONNECT_MS
        }

    private fun post(url: String, token: String, contentType: String, body: ByteArray): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            connectTimeout = CONNECT_MS; readTimeout = CONNECT_MS
            outputStream.write(body)
        }
}
