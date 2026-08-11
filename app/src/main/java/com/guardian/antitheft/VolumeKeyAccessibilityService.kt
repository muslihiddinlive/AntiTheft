package com.guardian.antitheft

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Yon (volume) tugma orqali maxfiy signal berish:
 *   - 3x tez Volume Up   -> qisqa audio yozish (AudioRecordService)
 *   - 3x tez Volume Down -> qisqa video yozish (VideoCaptureService)
 *   - Volume Down 2x tez + 3-marta bosib ushlab turish -> to'liq panik signal
 *     (signal ovozi + video + joylashuv, Telegram'ga)
 *
 * Bu FLAG_REQUEST_FILTER_KEY_EVENTS orqali ishlaydi, shuning uchun ekran
 * o'chiq (sleep) holatda ham tugma bosilishini ushlab qoladi. Ishlashi uchun
 * foydalanuvchi buni Sozlamalar -> Maxsus imkoniyatlar (Accessibility) dan
 * qo'lda yoqishi shart — bu Android tomonidan talab qilinadi va dasturiy
 * yo'l bilan avtomatik yoqib bo'lmaydi.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private val upTaps = mutableListOf<Long>()
    private val downTaps = mutableListOf<Long>()
    private var downPressStartedAt = 0L

    companion object {
        private const val GESTURE_WINDOW_MS = 1500L
        private const val HOLD_THRESHOLD_MS = 700L
        private const val REQUIRED_TAPS = 3
        private const val HOLD_REQUIRES_PRIOR_TAPS = 2
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    registerTap(upTaps, now)
                    if (countRecent(upTaps, now) >= REQUIRED_TAPS) {
                        upTaps.clear()
                        triggerAudio()
                    }
                }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    downPressStartedAt = now
                    registerTap(downTaps, now)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val heldMs = if (downPressStartedAt > 0) now - downPressStartedAt else 0L
                    // ushbu bosishdan OLDINGI tezkor bosishlar sonini hisoblaymiz
                    val priorTaps = countRecent(downTaps, now) - 1

                    if (heldMs >= HOLD_THRESHOLD_MS && priorTaps >= HOLD_REQUIRES_PRIOR_TAPS) {
                        downTaps.clear()
                        triggerPanicSignal()
                    } else if (countRecent(downTaps, now) >= REQUIRED_TAPS) {
                        downTaps.clear()
                        triggerVideo()
                    }
                }
            }
        }

        // false qaytaramiz: tugma tizimga ham uzatiladi, ovoz balandligi odatdagidek ishlayveradi
        return false
    }

    private fun registerTap(list: MutableList<Long>, now: Long) {
        list.add(now)
        while (list.isNotEmpty() && now - list.first() > GESTURE_WINDOW_MS) {
            list.removeAt(0)
        }
    }

    private fun countRecent(list: List<Long>, now: Long): Int =
        list.count { now - it <= GESTURE_WINDOW_MS }

    private fun triggerAudio() {
        startForegroundServiceSafely(Intent(this, AudioRecordService::class.java))
    }

    private fun triggerVideo() {
        startForegroundServiceSafely(Intent(this, VideoCaptureService::class.java))
    }

    private fun triggerPanicSignal() {
        startForegroundServiceSafely(Intent(this, AlarmService::class.java))
        startForegroundServiceSafely(Intent(this, VideoCaptureService::class.java))

        Thread {
            val prefs = getSharedPreferences("antitheft_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("bot_token", "").orEmpty()
            val chatId = prefs.getString("chat_id", "").orEmpty()
            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                val locationText = LocationHelper.getLastLocationText(this)
                TelegramSender.sendMessage(
                    token, chatId,
                    "🆘 PANIK SIGNAL (yon tugma orqali)!\n$locationText"
                )
            }
        }.start()
    }

    private fun startForegroundServiceSafely(intent: Intent) {
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* kerak emas */ }

    override fun onInterrupt() { /* kerak emas */ }
}
