package com.ghostbroot.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.ghostbroot.app.model.Panel
import org.json.JSONArray
import org.json.JSONObject

/**
 * WindowManager overlay panels (spec §1/§2).
 * - Recomputes layout on rotation / density change (configChanges on the
 *   activity + onConfigurationChanged forwarded here by the service).
 * - Falls back gracefully when overlay permission is denied: panel state is
 *   kept and retried when the service restarts; the API never errors out
 *   because of a missing overlay window.
 */
class PanelManager(private val context: Context) {
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = HashMap<String, View>()

    fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun render(panel: Panel, onEvent: (String, JSONObject) -> Unit) {
        // Remove + re-add keeps state consistent across orientation changes.
        remove(panel.id)
        if (!panel.visible) return
        if (!canDrawOverlay()) return // fallback: silent no-op, API state retained

        val root = PanelView.panelContainer(context, panel.title, panel.widthDp, panel.heightDp)
        val body = root.findViewWithTag<View>("body") as LinearLayout
        for (i in 0 until panel.components.length()) {
            val comp = panel.components.optJSONObject(i) ?: continue
            body.addView(PanelView.buildComponent(context, comp, onEvent))
        }

        val params = WindowManager.LayoutParams(
            PanelView.dp(context, panel.widthDp),
            PanelView.dp(context, panel.heightDp),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panel.xDp?.let { PanelView.dp(context, it) } ?: PanelView.dp(context, 30)
            y = panel.yDp?.let { PanelView.dp(context, it) } ?: PanelView.dp(context, 150)
            alpha = panel.alpha
        }
        addDragBehavior(root)
        try {
            wm.addView(root, params)
            views[panel.id] = root
        } catch (_: Exception) {
            // OEM without overlay support: ignore — panel remains in API state.
        }
    }

    fun updateCanvas(panelId: String, ops: JSONArray) {
        val root = views[panelId] ?: return
        findCanvas(root)?.setOps(ops)
    }

    fun remove(panelId: String) {
        val v = views.remove(panelId) ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    fun removeAll() { views.keys.toList().forEach { remove(it) } }

    private fun findCanvas(v: View): CanvasView? {
        if (v is CanvasView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findCanvas(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun addDragBehavior(view: View) {
        var startX = 0; var startY = 0
        var initX = 0; var initY = 0
        view.setOnTouchListener { _, event ->
            val p = view.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initX = p.x; initY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initX + (event.rawX.toInt() - startX)
                    p.y = initY + (event.rawY.toInt() - startY)
                    wm.updateViewLayout(view, p)
                    true
                }
                else -> false
            }
        }
    }
}
