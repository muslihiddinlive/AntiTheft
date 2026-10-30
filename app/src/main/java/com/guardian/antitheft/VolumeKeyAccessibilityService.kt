package com.guardian.antitheft

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Yon (volume) tugma orqali maxfiy signal berish:
 *   - 3x tez Volume Up   -> qisqa audio yozish (AudioRecordService)
 *   - Volume Up bosib ushlab turish (~0.7s, tez-tez bosilmagan holda)
 *     -> ekran yozuvi (ScreenRecordPermissionActivity orqali). DIQQAT:
 *     bu kombinatsiya faqat tizimning MediaProjection ruxsat dialogini
 *     ochadi — dialogni bosish hali ham qurilmani ushlab turgan odamdan
 *     talab qilinadi, buni Android talab qiladi va avtomatlashtirib
 *     bo'lmaydi.
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
    private var upPressStartedAt = 0L
    private var downPressStartedAt = 0L

    companion object {
        private const val GESTURE_WINDOW_MS = 1500L
        private const val HOLD_THRESHOLD_MS = 700L
        private const val REQUIRED_TAPS = 3
        private const val HOLD_REQUIRES_PRIOR_TAPS = 2
        private const val SCREEN_RECORD_DEFAULT_SEC = 20

        /**
         * true qilib qo'yilganda, keyingi MediaProjection ruxsat dialogida
         * "Hozir boshlash" / "Start now" tugmasi AVTOMATIK bosiladi.
         * BotCommandListener Telegram'dan /screenrecord kelganda shu flagni yoqadi.
         */
        @Volatile var autoClickScreenRecord = false
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
                    upPressStartedAt = now
                    registerTap(upTaps, now)
                    if (countRecent(upTaps, now) >= REQUIRED_TAPS) {
                        upTaps.clear()
                        triggerAudio()
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val heldMs = if (upPressStartedAt > 0) now - upPressStartedAt else 0L
                    // Yakka, uzoq bosish (tez-tez bosilgan combo'ning bir qismi bo'lmasa) -> ekran yozuvi
                    if (heldMs >= HOLD_THRESHOLD_MS && countRecent(upTaps, now) <= 1) {
                        upTaps.clear()
                        triggerScreenRecord()
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

    private fun triggerScreenRecord() {
        val prefs      = SecurePrefs.get(this)
        val dur        = prefs.getInt("screen_record_duration_sec", SCREEN_RECORD_DEFAULT_SEC)
        val withCamera = prefs.getBoolean("record_camera_with_screen", false)
        try {
            startActivity(
                Intent(this, ScreenRecordPermissionActivity::class.java).apply {
                    putExtra(ScreenRecordService.EXTRA_DURATION_SEC, dur)
                    putExtra(ScreenRecordService.EXTRA_SEND_TELEGRAM, false)
                    putExtra(ScreenRecordService.EXTRA_WITH_CAMERA, withCamera)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun triggerPanicSignal() {
        startForegroundServiceSafely(Intent(this, AlarmService::class.java))
        startForegroundServiceSafely(Intent(this, VideoCaptureService::class.java))

        Thread {
            val prefs = SecurePrefs.get(this)
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

    /**
     * Telegram'dan /screenrecord kelganda autoClickScreenRecord=true bo'ladi.
     * Shu holda MediaProjection ruxsat dialogi paydo bo'lishi bilan
     * "Hozir boshlash" / "Start now" tugmasi dasturiy ravishda bosiladi —
     * o'g'iri dialogni ko'rishga ulgurmaydi.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!autoClickScreenRecord) return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        if (clickStartNow(root)) autoClickScreenRecord = false
    }

    /**
     * Bir nechta strategiya bilan "Hozir boshlash" tugmasini topadi va bosadi.
     * - 1: matn bo'yicha (ko'p tilda)
     * - 2: view ID bo'yicha (android:id/button1 — standart dialog musbat tugma)
     * - 3: ota-view orqali (ba'zi OEM'larda to'g'ri node ota'ga bo'ladi)
     */
    private fun clickStartNow(root: AccessibilityNodeInfo): Boolean {
        // Ko'p tilda "Hozir boshlash" matnlari
        val labels = listOf(
            "Hozir boshlash", "Start now", "Начать", "Jetzt starten",
            "Commencer", "Iniciar", "Mulai sekarang", "立即开始", "지금 시작"
        )
        for (text in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (tryClick(node)) return true
            }
        }
        // android:id/button1 — AlertDialog'ning musbat tugmasi (har qanday tilda ishlaydi)
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (byId.isNotEmpty() && tryClick(byId[0])) return true

        // Systemui paketidagi har qanday bosish mumkin tugma
        if (clickableButtonIn(root, "com.android.systemui")) return true
        if (clickableButtonIn(root, "android"))              return true

        return false
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
        }
        // Ba'zi OEM'larda click event ota-node'da bo'ladi
        val parent = node.parent
        if (parent != null && parent.isClickable) {
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
        }
        return false
    }

    private fun clickableButtonIn(node: AccessibilityNodeInfo, pkg: String): Boolean {
        if (node.packageName?.toString() != pkg) return false
        if (node.className == "android.widget.Button" && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickableButtonIn(child, pkg)) return true
        }
        return false
    }

    override fun onInterrupt() { /* kerak emas */ }
}
