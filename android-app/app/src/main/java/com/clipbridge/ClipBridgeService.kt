package com.clipbridge

import android.app.*
import android.content.*
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClipBridgeService : Service() {

    companion object {
        const val TAG = "ClipBridgeService"
        const val NOTIF_CHANNEL = "clipbridge_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.clipbridge.STOP"
        const val ACTION_STATUS = "com.clipbridge.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var running = false
    private var lastSent = ""
    private var lastReceived = ""

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var clipboardManager: android.content.ClipboardManager

    // Clipboard polling job
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("clipbridge_prefs", MODE_PRIVATE)
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        startSync()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        webSocket?.close(1000, "Service stopped")
        client?.dispatcher?.executorService?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Core sync ───────────────────────────────────────────────

    private fun startSync() {
        running = true
        scope.launch { connectWithRetry() }
    }

    private suspend fun connectWithRetry() {
        var backoff = 1000L
        while (running) {
            try {
                connect()
                backoff = 1000L
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
                broadcastStatus("disconnected", "Reconnecting in ${backoff / 1000}s...")
                updateNotification("Reconnecting...")
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            }
        }
    }

    private suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val secret = prefs.getString("secret", "") ?: ""
        val deviceId = prefs.getString("device_id", android.os.Build.MODEL) ?: android.os.Build.MODEL

        if (serverUrl.isEmpty() || secret.isEmpty()) {
            cont.cancel(Exception("Server URL or secret not configured"))
            return@suspendCancellableCoroutine
        }

        val key = CryptoHelper.deriveKey(secret)
        val room = CryptoHelper.deriveRoom(secret)

        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Long-lived connection
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(serverUrl).build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                // Join room
                ws.send(JSONObject().apply {
                    put("type", "join")
                    put("room", room)
                    put("deviceId", deviceId)
                }.toString())

                startClipboardPolling(ws, key)
                broadcastStatus("connected", "Syncing with $deviceId")
                updateNotification("Connected ✓")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.getString("type")) {
                        "clip" -> {
                            val decrypted = CryptoHelper.decrypt(
                                msg.getString("data"),
                                msg.getString("iv"),
                                key
                            )
                            if (decrypted.isNotEmpty() && decrypted != lastReceived) {
                                lastReceived = decrypted
                                lastSent = decrypted // prevent echo
                                setClipboard(decrypted)
                                val preview = decrypted.take(40).replace("\n", "↵")
                                Log.i(TAG, "← Received: $preview")
                                broadcastStatus("connected", "Received: $preview")
                            }
                        }
                        "joined" -> {
                            val peers = msg.optInt("peers", 0)
                            Log.i(TAG, "Joined room. $peers peer(s) online.")
                        }
                        "error" -> Log.e(TAG, "Server: ${msg.optString("message")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Message parse error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                pollJob?.cancel()
                broadcastStatus("disconnected", "Connection closed")
                updateNotification("Disconnected")
                if (running) cont.resumeWith(Result.failure(Exception("Closed: $reason")))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                pollJob?.cancel()
                broadcastStatus("disconnected", t.message ?: "Connection failed")
                updateNotification("Connection failed")
                if (running) cont.resumeWith(Result.failure(t))
            }
        })

        cont.invokeOnCancellation {
            webSocket?.close(1000, "Cancelled")
            pollJob?.cancel()
        }
    }

    // ── Clipboard polling ────────────────────────────────────────

    private fun startClipboardPolling(ws: WebSocket, key: ByteArray) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (running && ws.send("")) {  // ws still open check via send("")
                try {
                    val current = getClipboard()
                    if (current.isNotEmpty() && current != lastSent && current != lastReceived) {
                        lastSent = current
                        val payload = CryptoHelper.encrypt(current, key)
                        ws.send(JSONObject().apply {
                            put("type", "clip")
                            put("data", payload.data)
                            put("iv", payload.iv)
                        }.toString())
                        val preview = current.take(40).replace("\n", "↵")
                        Log.i(TAG, "→ Sent: $preview")
                        broadcastStatus("connected", "Sent: $preview")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
                delay(300) // Poll every 300ms
            }
        }
    }

    // ── Clipboard helpers ────────────────────────────────────────

    private fun getClipboard(): String {
        return try {
            if (!clipboardManager.hasPrimaryClip()) return ""
            val clip = clipboardManager.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun setClipboard(text: String) {
        try {
            val clip = android.content.ClipData.newPlainText("ClipBridge", text)
            clipboardManager.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set clipboard: ${e.message}")
        }
    }

    // ── Notifications ────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL,
            "ClipBridge Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Clipboard sync status"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ClipBridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("ClipBridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: String, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        })
    }
}
