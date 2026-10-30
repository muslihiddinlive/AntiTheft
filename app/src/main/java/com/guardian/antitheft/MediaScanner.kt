package com.guardian.antitheft

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Qurilmadagi barcha media fayllarni skanlab, hajmi bo'yicha (kattadan kichikka)
 * tartiblangan ro'yxat qaytaradi.
 *
 * Manba 1: MediaStore (DCIM, Pictures, Downloads, Music …)
 * Manba 2: filesDir/recordings/ — AntiTheft'ning o'z yozuvlari
 */
object MediaScanner {

    data class MediaItem(
        val file: File,
        val name: String,
        val size: Long,
        val mimeType: String
    )

    fun scanAll(context: Context): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        scanStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,  "video/mp4",  items)
        scanStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", items)
        scanStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,  "audio/mpeg", items)
        scanInternal(context, items)
        // Kattadan kichikka, bo'sh fayllarni olib tashlaymiz
        return items.filter { it.size > 0 }.sortedByDescending { it.size }.distinctBy { it.file.absolutePath }
    }

    private fun scanStore(context: Context, storeUri: android.net.Uri, fallbackMime: String, out: MutableList<MediaItem>) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE
        )
        try {
            context.contentResolver.query(storeUri, proj, null, null, null)?.use { cur ->
                val iId   = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iData = cur.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iMime = cur.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (cur.moveToNext()) {
                    val path = if (iData >= 0) cur.getString(iData) else null ?: continue
                    val file = File(path)
                    if (!file.exists() || file.length() == 0L) continue
                    out.add(MediaItem(
                        file    = file,
                        name    = cur.getString(iName) ?: file.name,
                        size    = cur.getLong(iSize),
                        mimeType= if (iMime >= 0) cur.getString(iMime) ?: fallbackMime else fallbackMime
                    ))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun scanInternal(context: Context, out: MutableList<MediaItem>) {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) return
        dir.listFiles { f -> f.extension == "mp4" }?.forEach { f ->
            out.add(MediaItem(file = f, name = f.name, size = f.length(), mimeType = "video/mp4"))
        }
    }
}
