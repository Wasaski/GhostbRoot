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
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ghostbroot.app.ui.PanelView

/**
 * Connection screen (spec §4):
 * server state, local address, port, auth state, token, Termux detection,
 * copy-to-clipboard. Manual config only — no auto-discovery required.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var infoText: TextView
    private lateinit var tokenText: TextView
    private var token: String = ""
    private var port: Int = GhostService.DEFAULT_PORT

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
        val intent = Intent(this, GhostService::class.java)
            .setAction(GhostService.ACTION_START)
            .putExtra(GhostService.EXTRA_TOKEN, token)
            .putExtra(GhostService.EXTRA_PORT, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refresh()
    }

    private fun stopServer() {
        startService(Intent(this, GhostService::class.java).setAction(GhostService.ACTION_STOP))
        refresh()
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
        stateText.text = "Server: ready to start (port $port)"
        val termux = if (TermuxDetect.isTermuxPresent()) "detected" else "not detected on this device"
        infoText.text = "host: 127.0.0.1\nport: $port\nauth: token required\ntermux: $termux\n" +
            "python: ${TermuxDetect.termuxPath()}"
        tokenText.text = "token: $token"
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // PanelWindow re-renders on next render cycle; dp layout keeps it adaptive.
    }
}
