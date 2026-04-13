package com.clipbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("clipbridge_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            val secret = prefs.getString("secret", "")
            
            if (autoStart && !secret.isNullOrBlank()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ClipBridgeService::class.java)
                )
            }
        }
    }
}
