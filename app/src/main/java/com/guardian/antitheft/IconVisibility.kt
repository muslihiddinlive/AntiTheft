package com.guardian.antitheft

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconVisibility {

    /** true — ikonka yashiriladi, false — qayta ko'rinadi */
    fun setHidden(context: Context, hidden: Boolean) {
        val alias = ComponentName(context, "com.guardian.antitheft.MainActivityAlias")
        val newState = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        context.packageManager.setComponentEnabledSetting(
            alias, newState, PackageManager.DONT_KILL_APP
        )

        SecurePrefs.get(context)
            .edit().putBoolean("icon_hidden", hidden).apply()
    }

    fun isHidden(context: Context): Boolean =
        SecurePrefs.get(context)
            .getBoolean("icon_hidden", false)
}
