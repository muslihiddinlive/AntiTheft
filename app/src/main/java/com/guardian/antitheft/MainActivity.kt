package com.guardian.antitheft

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var tvStatus: TextView
    private lateinit var btnAdmin: Button
    private lateinit var btnCamera: Button
    private lateinit var btnSettings: Button

    // Kamera + lokatsiya + SIM ruxsatlarini birgalikda so'rash
    private val multiPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    // Bildirishnoma ruxsati (API 33+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm            = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        tvStatus   = findViewById(R.id.tv_status)
        btnAdmin   = findViewById(R.id.btn_admin)
        btnCamera  = findViewById(R.id.btn_camera)
        btnSettings = findViewById(R.id.btn_settings)

        // Device Admin ni faollashtirish
        btnAdmin.setOnClickListener {
            if (!dpm.isAdminActive(adminComponent)) {
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).also { i ->
                    i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    i.putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_description)
                    )
                    startActivity(i)
                }
            }
        }

        // Kamera + lokatsiya + SIM ruxsatlari
        btnCamera.setOnClickListener {
            val needed = mutableListOf<String>()
            if (!hasCameraPermission()) needed.add(Manifest.permission.CAMERA)
            if (!hasLocationPermission()) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!hasPhoneStatePermission()) needed.add(Manifest.permission.READ_PHONE_STATE)

            if (needed.isNotEmpty()) {
                multiPermLauncher.launch(needed.toTypedArray())
            } else {
                updateStatus()
            }
            // API 33+: bildirishnoma ruxsati ham kerak (foreground service uchun)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // Sozlamalar ekraniga o'tish
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isAdmin   = dpm.isAdminActive(adminComponent)
        val hasCamera = hasCameraPermission()
        val hasLocation = hasLocationPermission()
        val hasPhoneState = hasPhoneStatePermission()
        val prefs     = getSharedPreferences("antitheft_prefs", MODE_PRIVATE)
        val hasToken  = !prefs.getString("bot_token", "").isNullOrEmpty()
        val hasChatId = !prefs.getString("chat_id",   "").isNullOrEmpty()
        val allReady  = isAdmin && hasCamera && hasToken && hasChatId

        tvStatus.text = buildString {
            appendLine("Device Admin : ${if (isAdmin)   "✅ Faol"       else "❌ Faol emas"}")
            appendLine("Kamera       : ${if (hasCamera) "✅ Ruxsat bor" else "❌ Ruxsat yo'q"}")
            appendLine("Lokatsiya    : ${if (hasLocation) "✅ Ruxsat bor" else "⚪ Ixtiyoriy"}")
            appendLine("SIM kuzatuv  : ${if (hasPhoneState) "✅ Ruxsat bor" else "⚪ Ixtiyoriy"}")
            appendLine("Bot Token    : ${if (hasToken)  "✅ Sozlangan"  else "❌ Sozlanmagan"}")
            appendLine("Chat ID      : ${if (hasChatId) "✅ Sozlangan"  else "❌ Sozlanmagan"}")
            appendLine()
            append(if (allReady) "🛡️  Himoya TO'LIQ FAOL!" else "⚠️  Barcha qadamlarni bajaring")
        }

        btnAdmin.text      = if (isAdmin)   "Admin: ✅ Faol"  else "1️⃣  Device Admin'ni faollashtirish"
        btnAdmin.isEnabled = !isAdmin

        btnCamera.text      = if (hasCamera) "Kamera: ✅ Ruxsat" else "2️⃣  Kamera/Lokatsiya/SIM ruxsatlarini so'rash"
        btnCamera.isEnabled = !hasCamera
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasPhoneStatePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
