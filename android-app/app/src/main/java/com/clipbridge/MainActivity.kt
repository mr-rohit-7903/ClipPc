package com.clipbridge

import android.content.*
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("clipbridge_prefs", MODE_PRIVATE)

        bindViews()
        loadPrefs()
        setupListeners()
        registerStatusReceiver()
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
}
