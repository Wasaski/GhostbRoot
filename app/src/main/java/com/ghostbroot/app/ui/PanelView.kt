package com.ghostbroot.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * Builds Android views from GhostbRoot component JSON (spec §2).
 * Sizes are dp-based and adapt to density and orientation (spec §1).
 */
object PanelView {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun buildComponent(context: Context, spec: JSONObject, onEvent: (String, JSONObject) -> Unit): View {
        val id = spec.optString("id", "")
        val text = spec.optString("text", spec.optString("label", ""))
        fun baseParams(marginDp: Int = 6): LinearLayout.LayoutParams {
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            p.setMargins(dp(context, marginDp), dp(context, marginDp), dp(context, marginDp), dp(context, marginDp))
            return p
        }

        return when (spec.optString("type")) {
            "label" -> TextView(context).apply {
                this.text = text.ifEmpty { "Label" }
                textSize = spec.optDouble("textSize", 14.0).toFloat()
                setTextColor(parseColor(spec.optString("color", "#E6EDF3"), Color.WHITE))
                layoutParams = baseParams()
                tag = id
            }
            "button" -> Button(context).apply {
                this.text = text.ifEmpty { "Button" }
                setOnClickListener {
                    onEvent("component.clicked", JSONObject().put("componentId", id).put("text", text))
                }
                layoutParams = baseParams()
                tag = id
            }
            "input" -> EditText(context).apply {
                hint = spec.optString("hint", text.ifEmpty { "Type here" })
                setText(spec.optString("value", ""))
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                layoutParams = baseParams()
                tag = id
            }
            "slider" -> SeekBar(context).apply {
                max = spec.optInt("max", 100)
                progress = spec.optInt("value", 0)
                layoutParams = baseParams()
                tag = id
            }
            "switch" -> Switch(context).apply {
                this.text = text.ifEmpty { "Switch" }
                isChecked = spec.optBoolean("checked", false)
                setTextColor(Color.WHITE)
                layoutParams = baseParams()
                tag = id
            }
            "canvas" -> CanvasView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(context, spec.optInt("width", 320)),
                    dp(context, spec.optInt("height", 200))
                ).apply { setMargins(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6)) }
                tag = id
            }
            "spacer" -> View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, spec.optInt("height", 12))
                )
                tag = id
            }
            else -> TextView(context).apply {
                this.text = "Unsupported component: ${spec.optString("type")}"
                setTextColor(Color.LTGRAY)
                layoutParams = baseParams()
                tag = id
            }
        }
    }

    fun panelContainer(context: Context, title: String, widthDp: Int, heightDp: Int): LinearLayout {
        val corner = dp(context, 12).toFloat()
        val bg = GradientDrawable().apply {
            setColor(0xE611141C.toInt())
            setCornerRadius(corner)
            setStroke(dp(context, 1), 0xFF2D3748.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            layoutParams = ViewGroup.LayoutParams(dp(context, widthDp), dp(context, heightDp))
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }
        val header = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 40)
            )
        }
        root.addView(header)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            tag = "body"
        }
        root.addView(body)
        return root
    }

    private fun parseColor(s: String, fallback: Int): Int = try {
        if (s.startsWith("#")) Color.parseColor(s) else fallback
    } catch (_: Exception) { fallback }
}
