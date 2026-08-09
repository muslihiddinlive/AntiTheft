package com.guardian.antitheft

import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Qurilmani topish uchun baland ovozda signal chalinadi va tebranadi.
 * ~15 soniya davom etadi, keyin o'zi to'xtaydi.
 */
class AlarmService : Service() {

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val DURATION_MS = 15_000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAlarm()
        handler.postDelayed({ stopSelf() }, DURATION_MS)
        return START_NOT_STICKY
    }

    private fun startAlarm() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, DURATION_MS.toInt())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 500, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
        vibrator?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
