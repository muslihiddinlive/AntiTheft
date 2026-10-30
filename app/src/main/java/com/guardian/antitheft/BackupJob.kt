package com.guardian.antitheft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Backup orkestratori — foreground Service sifatida ishlaydi.
 *
 * Algoritem:
 *  1. Media skan: barcha fayllar hajmi bo'yicha (kattadan kichikka)
 *  2. >= 20 MB → Google Drive (ulangan akkauntlar ketma-ket; biri to'lsa keyingisiga o'tadi)
 *  3. <  20 MB → Telegram botiga yuboriladi
 *  4. Oldin yuborilgan fayllar o'tkazib yuboriladi (BackupHelper.isAlreadyBacked)
 *  5. Har bir muhim qadam Telegram'ga xabar sifatida yuboriladi
 */
class BackupJob : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) { stopSelf(); return START_NOT_STICKY }
        isRunning = true
        startForeground(NOTIF_ID, buildNotif("Backup tayyorlanmoqda..."))
        Thread { runBackup(); isRunning = false; stopSelf() }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { super.onDestroy(); isRunning = false }

    // ── Asosiy logika ────────────────────────────────────────────────

    private fun runBackup() {
        val prefs   = SecurePrefs.get(this)
        val token   = prefs.getString("bot_token", "").orEmpty()
        val chatId  = prefs.getString("chat_id",   "").orEmpty()
        fun tg(msg: String) { if (token.isNotEmpty() && chatId.isNotEmpty()) TelegramSender.sendMessage(token, chatId, msg) }

        val accounts = BackupHelper.getLinkedAccounts(this)

        // Media skan
        notif("Media skanlanmoqda...")
        val all   = MediaScanner.scanAll(this)
        val large = all.filter { it.size >= DRIVE_MIN_BYTES }   // >= 20 MB → Drive
        val small = all.filter { it.size <  DRIVE_MIN_BYTES }   // <  20 MB → Telegram

        tg("🔄 Backup boshlandi\n" +
           "📁 Google Drive: ${large.size} ta fayl (≥20 MB)\n" +
           "📨 Telegram:     ${small.size} ta fayl (<20 MB)\n" +
           "🔗 Akkauntlar:   ${accounts.size} ta")

        // ── Drive backup ─────────────────────────────────────────────
        var driveOk = 0; var driveSkip = 0; var driveFail = 0
        var accIdx  = 0
        val tokenCache   = mutableMapOf<String, String?>()
        val folderCache  = mutableMapOf<String, String?>()   // accName → folderId

        for ((i, item) in large.withIndex()) {
            if (BackupHelper.isAlreadyBacked(this, item)) { driveSkip++; continue }
            if (accIdx >= accounts.size) { driveFail++; continue }

            notif("Drive ${i+1}/${large.size}: ${item.name}")
            var uploaded = false

            while (accIdx < accounts.size && !uploaded) {
                val acc = accounts[accIdx]

                // Token
                val driveToken = tokenCache.getOrPut(acc) { DriveUploader.getToken(this, acc) }
                if (driveToken == null) {
                    tg("⚠️ $acc — token olinmadi, keyingi akkauntga o'tildi")
                    tokenCache.remove(acc); accIdx++; continue
                }

                // Xotira tekshiruvi
                val free = DriveUploader.getFreeBytes(driveToken)
                when {
                    free == -2L -> { // Token eskirgan
                        DriveUploader.invalidateToken(this, driveToken)
                        tokenCache.remove(acc); continue
                    }
                    free != -1L && free < item.size -> {
                        tg("💾 $acc — xotira to'ldi (${mb(free)} MB bo'sh), keyingisiga o'tildi")
                        accIdx++; continue
                    }
                }

                // Papka
                val folder = folderCache.getOrPut(acc) { DriveUploader.getOrCreateFolder(driveToken) }

                // Yuklash
                val res = DriveUploader.upload(driveToken, item.file, item.mimeType, folder)
                if (res.ok) {
                    driveOk++
                    BackupHelper.markBacked(this, item)
                    tg("✅ ${item.name} (${mb(item.size)} MB) → $acc")
                    uploaded = true
                } else if (res.error.contains("401") || res.error.contains("403")) {
                    DriveUploader.invalidateToken(this, driveToken)
                    tokenCache.remove(acc)
                } else {
                    tg("❌ ${item.name} — ${res.error}")
                    driveFail++; uploaded = true // keyingi faylga o'tamiz
                }
            }
            if (!uploaded) driveFail++
        }

        // ── Telegram backup ──────────────────────────────────────────
        var tgOk = 0; var tgFail = 0
        if (token.isNotEmpty() && chatId.isNotEmpty()) {
            for ((i, item) in small.withIndex()) {
                if (BackupHelper.isAlreadyBacked(this, item)) continue
                notif("Telegram ${i+1}/${small.size}: ${item.name}")

                val bytes = try { item.file.readBytes() } catch (e: Exception) { tgFail++; continue }
                val cap   = "${item.name} (${mb(item.size)} MB)"

                val sent = when {
                    item.mimeType.startsWith("video/") ->
                        TelegramSender.sendVideo(token, chatId, bytes, cap)
                    item.mimeType.startsWith("image/") ->
                        TelegramSender.sendPhoto(token, chatId, bytes, cap)
                    item.mimeType.startsWith("audio/") ->
                        TelegramSender.sendAudio(token, chatId, bytes, cap)
                    else ->
                        TelegramSender.sendDocument(token, chatId, bytes, item.name, cap)
                }
                if (sent) { tgOk++; BackupHelper.markBacked(this, item) } else tgFail++
            }
        }

        // ── Yakuniy hisobot ──────────────────────────────────────────
        tg("✅ Backup yakunlandi!\n" +
           "📁 Drive: $driveOk ta yuklandi, $driveSkip ta oldin yuborilgan, $driveFail ta xato\n" +
           "📨 Telegram: $tgOk ta yuborildi, $tgFail ta xato")
        notif("Backup tugadi — Drive:$driveOk Telegram:$tgOk")
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun notif(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiTheft Backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()

    // ── Yordamchilar ─────────────────────────────────────────────────

    private fun mb(bytes: Long) = "%.1f".format(bytes / 1024.0 / 1024.0)

    companion object {
        private const val CHANNEL_ID     = "antitheft_backup_job"
        private const val NOTIF_ID       = 48
        private const val DRIVE_MIN_BYTES = 20L * 1024 * 1024   // 20 MB

        @Volatile var isRunning = false

        fun start(context: Context) {
            if (isRunning) return
            context.startForegroundService(Intent(context, BackupJob::class.java))
        }
    }
}
