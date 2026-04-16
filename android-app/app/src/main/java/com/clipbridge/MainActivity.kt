package com.clipbridge

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
    }

    private lateinit var prefs: SharedPreferences
    private var statusReceiver: BroadcastReceiver? = null

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var tvLastActivity: TextView
    private lateinit var statusDot: View
    private lateinit var etServerUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnSave: Button

    // Accessibility views
    private lateinit var cardAccessibility: CardView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibility: Button

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("clipbridge_prefs", MODE_PRIVATE)

        bindViews()
        loadPrefs()
        setupListeners()
        registerStatusReceiver()
        requestPermissionsIfNeeded()
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLastActivity = findViewById(R.id.tvLastActivity)
        statusDot = findViewById(R.id.statusDot)
        etServerUrl = findViewById(R.id.etServerUrl)
        etSecret = findViewById(R.id.etSecret)
        etDeviceId = findViewById(R.id.etDeviceId)
        btnToggle = findViewById(R.id.btnToggle)
        btnSave = findViewById(R.id.btnSave)

        // Accessibility views
        cardAccessibility = findViewById(R.id.cardAccessibility)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)
    }

    private fun loadPrefs() {
        etServerUrl.setText(prefs.getString("server_url", "ws://YOUR_SERVER_IP:8765"))
        etSecret.setText(prefs.getString("secret", ""))
        etDeviceId.setText(prefs.getString("device_id", android.os.Build.MODEL))
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            savePrefs()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            if (isRunning) stopService() else startService()
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun savePrefs() {
        prefs.edit().apply {
            putString("server_url", etServerUrl.text.toString().trim())
            putString("secret", etSecret.text.toString().trim())
            putString("device_id", etDeviceId.text.toString().trim())
            apply()
        }
    }

    private fun startService() {
        if (etSecret.text.isNullOrBlank()) {
            Toast.makeText(this, "Please enter a shared secret", Toast.LENGTH_SHORT).show()
            return
        }
        // Check notification permission (required for foreground service on Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
            return
        }

        // Warn if accessibility service is not enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "⚠️ Enable Accessibility Permission for clipboard sync to work",
                Toast.LENGTH_LONG
            ).show()
        }

        savePrefs()

        val intent = Intent(this, ClipBridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isRunning = true
        updateToggleButton()
        setStatus("connecting", "Connecting...")
    }

    private fun stopService() {
        val intent = Intent(this, ClipBridgeService::class.java).apply {
            action = ClipBridgeService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        updateToggleButton()
        setStatus("disconnected", "Stopped")
    }

    private fun updateToggleButton() {
        btnToggle.text = if (isRunning) "Stop Sync" else "Start Sync"
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isRunning) getColor(R.color.danger) else getColor(R.color.accent)
        )
    }

    private fun setStatus(status: String, message: String) {
        tvStatus.text = when (status) {
            "connected" -> "● Connected"
            "connecting" -> "○ Connecting..."
            else -> "○ Disconnected"
        }
        tvStatus.setTextColor(when (status) {
            "connected" -> getColor(R.color.success)
            "connecting" -> getColor(R.color.warning)
            else -> getColor(R.color.danger)
        })
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when (status) {
                "connected" -> getColor(R.color.success)
                "connecting" -> getColor(R.color.warning)
                else -> getColor(R.color.danger)
            }
        )
        tvLastActivity.text = message
    }

    private fun registerStatusReceiver() {
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(ClipBridgeService.EXTRA_STATUS) ?: "disconnected"
                val message = intent.getStringExtra(ClipBridgeService.EXTRA_MESSAGE) ?: ""
                runOnUiThread {
                    setStatus(status, message)
                    if (status == "connected") {
                        isRunning = true
                        updateToggleButton()
                    }
                }
            }
        }
        registerReceiver(
            statusReceiver,
            IntentFilter(ClipBridgeService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        statusReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    // ── Accessibility Service ───────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${ClipBridgeAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').run {
            setString(enabledServices)
            while (hasNext()) {
                if (next().equals(serviceName, ignoreCase = true)) return true
            }
            false
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (enabled) "✓ Enabled" else "Not enabled"
        tvAccessibilityStatus.setTextColor(
            getColor(if (enabled) R.color.success else R.color.danger)
        )
        btnAccessibility.text = if (enabled) "Accessibility Enabled ✓" else "Open Accessibility Settings"
        btnAccessibility.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (enabled) R.color.success else R.color.warning)
        )
    }

    // ── Permissions ──────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Notification permission is required for sync", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
