package com.ghostbroot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ghostbroot.app.ui.PanelView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connection screen (spec §4):
 * server state, local address, port, auth state, token, Termux detection,
 * copy-to-clipboard. Manual config only — no auto-discovery required.
 *
 * The screen probes GET /api/v1/status after (re)starting so the user gets
 * immediate feedback: "Server: RUNNING" or the last error.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var infoText: TextView
    private lateinit var tokenText: TextView
    private var token: String = ""
    private var port: Int = GhostService.DEFAULT_PORT
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = TokenStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PanelView.dp(this@MainActivity, 16), PanelView.dp(this@MainActivity, 16), PanelView.dp(this@MainActivity, 16), PanelView.dp(this@MainActivity, 16))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        val title = TextView(this).apply {
            text = "GhostbRoot"
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Local API server for Termux (127.0.0.1 only)"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        root.addView(subtitle)

        stateText = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        root.addView(stateText)

        infoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A202C.toInt())
            setPadding(PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12))
        }
        root.addView(infoText)

        tokenText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A202C.toInt())
            setPadding(PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12), PanelView.dp(this@MainActivity, 12))
        }
        root.addView(tokenText)

        root.addView(Button(this).apply {
            text = "Copy connection info"
            setOnClickListener { copyConnection() }
        })
        root.addView(Button(this).apply {
            text = "Start server"
            setOnClickListener { startServer() }
        })
        root.addView(Button(this).apply {
            text = "Stop server"
            setOnClickListener { stopServer() }
        })
        root.addView(Button(this).apply {
            text = "Test API"
            setOnClickListener { probeAsync("Manual test") }
        })
        root.addView(Button(this).apply {
            text = "Regenerate token"
            setOnClickListener {
                token = TokenStore.regenerate(this@MainActivity)
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener { requestOverlayPermission() }
        })

        setContentView(scroll)
        refresh()
    }

    private fun startServer() {
        stateText.text = "Starting server..."
        val intent = Intent(this, GhostService::class.java)
            .setAction(GhostService.ACTION_START)
            .putExtra(GhostService.EXTRA_TOKEN, token)
            .putExtra(GhostService.EXTRA_PORT, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        probeAsync("Start")
    }

    private fun stopServer() {
        startService(Intent(this, GhostService::class.java).setAction(GhostService.ACTION_STOP))
        stateText.text = "Server: stopped"
        refresh()
    }

    /**
     * Probes GET /api/v1/status from the app itself (no curl needed) and
     * updates the screen. Runs off the UI thread; safe to call repeatedly.
     */
    private fun probeAsync(reason: String) {
        if (polling) return
        polling = true
        Thread {
            var lastMsg = "no response"
            for (attempt in 1..3) {
                try {
                    val conn = (URL("http://127.0.0.1:$port/api/v1/status").openConnection()) as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val boundPort = org.json.JSONObject(body).optInt("port", port)
                        runOnUiThread {
                            port = boundPort
                            stateText.text = "Server: RUNNING (port $boundPort)"
                            refresh()
                        }
                        polling = false
                        return@Thread
                    }
                    lastMsg = "HTTP $code"
                } catch (e: Exception) {
                    lastMsg = "${e.javaClass.simpleName}: ${e.message}"
                }
                Thread.sleep(800)
            }
            val msg = lastMsg
            runOnUiThread {
                stateText.text = "Server: NOT responding ($reason) - $msg\nForce-stop the app (Settings > Apps > GhostbRoot > Force stop), then tap Start again."
                refresh()
            }
            polling = false
        }.apply { isDaemon = true }.start()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun copyConnection() {
        val text = "GhostbRoot connection\n" +
            "host=127.0.0.1\nport=$port\ntoken=$token\n" +
            "sdk:\n  gb = GhostbRoot(host=\"127.0.0.1\", port=$port, token=\"$token\")\n"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ghostbroot-connection", text))
    }

    private fun refresh() {
        val running = stateText.text.toString().contains("RUNNING")
        if (!running && !stateText.text.toString().contains("NOT responding"))
            stateText.text = "Server: ready to start (port $port)"
        val termux = if (TermuxDetect.isTermuxInstalled(this)) "detected (com.termux)" else "not detected on this device"
        infoText.text = "host: 127.0.0.1\nport: $port\nauth: token required\ntermux: $termux\n" +
            "python: ${TermuxDetect.termuxPath()}"
        tokenText.text = "token: $token"
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // PanelWindow re-renders on next render cycle; dp layout keeps it adaptive.
    }
}
