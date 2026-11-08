package com.ghostbroot.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ghostbroot.app.server.EventBus
import com.ghostbroot.app.server.GhostServer
import com.ghostbroot.app.server.Router
import com.ghostbroot.app.ui.PanelManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service hosting the GhostbRoot loopback server (spec §2/§8).
 * The APK runs independently; the server starts on demand via the activity
 * and is restartable by the system (START_STICKY) — panel state survives in
 * [Router.panels] for the process lifetime.
 */
class GhostService : Service() {

    companion object {
        const val ACTION_START = "com.ghostbroot.app.START"
        const val ACTION_STOP = "com.ghostbroot.app.STOP"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8765
    }

    private var server: GhostServer? = null
    private lateinit var panelManager: PanelManager

    override fun onCreate() {
        super.onCreate()
        panelManager = PanelManager(this)
        wireRouter()
    }

    private fun wireRouter() {
        Router.sink = object : Router.Sink {
            override fun onPanelChanged(panelId: String) {
                val panel = Router.panels[panelId]
                // Delete path: panel no longer in map -> remove view.
                if (panel == null) panelManager.remove(panelId)
                else runOnMain { panelManager.render(panel, ::emit) }
            }

            override fun onDraw(panelId: String, ops: JSONArray) {
                runOnMain { panelManager.updateCanvas(panelId, ops) }
            }

            override fun onAnimate(panelId: String, spec: JSONObject) {
                // Animations are applied to the panel root via CanvasView when
                // present; full-view animation is best-effort (see README).
                runOnMain {
                    val panel = Router.panels[panelId] ?: return@runOnMain
                    // Re-render is cheap and keeps animation state visible.
                    panelManager.render(panel, ::emit)
                }
            }
        }
    }

    private fun emit(type: String, payload: JSONObject) {
        EventBus.broadcast(type, payload)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: TokenStore.get(this)
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        if (server == null) {
            server = GhostServer(port = port, token = token, onEvent = ::emit).also { it.start() }
            EventBus.broadcast("server.started", JSONObject().put("port", port))
        }
        // Re-render all panels after restart (e.g. system killed the service).
        runOnMain { Router.panels.values.forEach { panelManager.render(it, ::emit) } }
        return START_STICKY
    }

    private fun stopServer() {
        server?.stop()
        server = null
        panelManager.removeAll()
    }

    private fun startForegroundNotification() {
        val channelId = "ghostbroot_server"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "GhostbRoot Server", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GhostbRoot")
            .setContentText("Local API server running on 127.0.0.1")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Persists the connection token until the user regenerates it (spec §4). */
object TokenStore {
    private const val PREFS = "ghostbroot"
    private const val KEY = "token"

    fun get(context: android.content.Context): String {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val created = com.ghostbroot.app.server.Auth.generate()
        prefs.edit().putString(KEY, created).apply()
        return created
    }

    fun regenerate(context: android.content.Context): String {
        val created = com.ghostbroot.app.server.Auth.generate()
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, created).apply()
        return created
    }
}

/** Termux detection for the connection screen (spec §4) — best effort, no hard dependency. */
object TermuxDetect {
    fun isTermuxPresent(): Boolean {
        val candidates = listOf(
            "/data/data/com.termux",
            "/data/data/com.termux.termux",
            "/data/data/com.termux/files/usr/bin/bash"
        )
        return candidates.any { java.io.File(it).exists() }
    }

    fun termuxPath(): String = "/data/data/com.termux/files/usr/bin/python"
}
