package com.guardian.antitheft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Qurilma qayta ishga tushganda, agar navbatda yuborilmagan suratlar bo'lsa,
 * PendingSendService'ni ishga tushiradi (internet qaytishini kutish uchun).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (PendingQueue.hasPending(context)) {
                PendingSendService.start(context)
            }
        }
    }
}
