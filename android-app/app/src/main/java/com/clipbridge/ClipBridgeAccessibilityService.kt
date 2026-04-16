package com.clipbridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ClipBridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipBridgeA11y"
        const val ACTION_CLIPBOARD_CHANGED = "com.clipbridge.CLIPBOARD_CHANGED"
        const val EXTRA_CLIP_TEXT = "clip_text"
    }

    private var lastClipText: String = ""
    private var clipboardManager: ClipboardManager? = null

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        Log.i(TAG, "Accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                readAndBroadcastClipboard()
            }
        }
    }

    private fun readAndBroadcastClipboard() {
        try {
            val cm = clipboardManager ?: return
            if (!cm.hasPrimaryClip()) return

            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return

            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isEmpty() || text == lastClipText) return

            lastClipText = text

            val intent = Intent(ACTION_CLIPBOARD_CHANGED).apply {
                putExtra(EXTRA_CLIP_TEXT, text)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            val preview = text.take(40).replace("\n", "↵")
            Log.d(TAG, "Clipboard changed: $preview")
        } catch (e: Exception) {
            Log.w(TAG, "Error reading clipboard: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}
