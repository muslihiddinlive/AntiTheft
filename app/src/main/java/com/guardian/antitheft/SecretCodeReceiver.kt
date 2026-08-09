package com.guardian.antitheft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telefon terish ekranida *#*#8228#*#* terilsa, ilova ikonkasi qayta ko'rinadi.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IconVisibility.setHidden(context, false)
    }
}
