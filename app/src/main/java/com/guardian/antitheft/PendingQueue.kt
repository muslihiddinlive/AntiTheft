package com.guardian.antitheft

import android.content.Context
import java.io.File

/**
 * Internet yo'q bo'lganda yuborilmagan suratlarni diskda saqlab turadi.
 * Har bir surat: pending_photos/<timestamp>.jpg + <timestamp>.caption (matn)
 */
object PendingQueue {

    private const val DIR_NAME = "pending_photos"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Yangi suratni navbatga qo'shadi */
    fun enqueue(context: Context, photo: ByteArray, caption: String) {
        val id = System.currentTimeMillis()
        val photoFile = File(dir(context), "$id.jpg")
        val captionFile = File(dir(context), "$id.caption")
        photoFile.writeBytes(photo)
        captionFile.writeText(caption)
    }

    data class PendingItem(val id: Long, val photo: ByteArray, val caption: String, val photoFile: File)

    /** Navbatdagi barcha suratlarni o'qib qaytaradi (eng eskisidan boshlab) */
    fun listAll(context: Context): List<PendingItem> {
        val d = dir(context)
        val jpgFiles = d.listFiles { f -> f.extension == "jpg" } ?: return emptyList()
        return jpgFiles.sortedBy { it.name }.mapNotNull { photoFile ->
            val id = photoFile.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            val captionFile = File(d, "$id.caption")
            val caption = if (captionFile.exists()) captionFile.readText() else ""
            PendingItem(id, photoFile.readBytes(), caption, photoFile)
        }
    }

    /** Muvaffaqiyatli yuborilgan elementni navbatdan o'chiradi */
    fun remove(context: Context, item: PendingItem) {
        item.photoFile.delete()
        File(dir(context), "${item.id}.caption").delete()
    }

    fun hasPending(context: Context): Boolean =
        dir(context).listFiles { f -> f.extension == "jpg" }?.isNotEmpty() == true
}
