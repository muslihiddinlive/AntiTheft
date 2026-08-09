package com.guardian.antitheft

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Olingan suratni telefon galereyasiga (Pictures/AntiTheft) saqlaydi.
 * API 29+ da MediaStore (scoped storage), pastroqda to'g'ridan-to'g'ri fayl tizimiga yozadi.
 */
object GallerySaver {

    fun save(context: Context, photo: ByteArray): Boolean {
        val fileName = "AntiTheft_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AntiTheft")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(photo) } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                val targetDir = File(picturesDir, "AntiTheft").apply { if (!exists()) mkdirs() }
                val targetFile = File(targetDir, fileName)
                FileOutputStream(targetFile).use { it.write(photo) }
                // Galereya darhol ko'rishi uchun media skanerini xabardor qilamiz
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(targetFile.absolutePath), arrayOf("image/jpeg"), null
                )
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
