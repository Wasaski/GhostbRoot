package com.ghostbroot.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom canvas component (spec §2: canvas control).
 * Drawing ops come from POST /api/v1/panels/{id}/canvas/draw:
 * clear | rect | circle | line | text | arc (bitmap not rendered — see README fallbacks).
 */
class CanvasView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ops = ArrayList<JSONObject>()

    fun setOps(newOps: JSONArray) {
        ops.clear()
        for (i in 0 until newOps.length()) newOps.optJSONObject(i)?.let { ops.add(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (op in ops) {
            val color = parseColor(op.optString("color", "#E6EDF3"), Color.WHITE)
            paint.color = color
            paint.alpha = (op.optDouble("alpha", 1.0) * 255).toInt().coerceIn(0, 255)
            paint.textSize = op.optDouble("textSize", 14.0).toFloat() * resources.displayMetrics.density
            paint.strokeWidth = op.optDouble("strokeWidth", 2.0).toFloat() * resources.displayMetrics.density
            paint.style = if (op.optString("style", "fill") == "stroke") Paint.Style.STROKE else Paint.Style.FILL
            val d = resources.displayMetrics.density
            when (op.optString("op")) {
                "clear" -> canvas.drawColor(parseColor(op.optString("color", "#00000000"), Color.TRANSPARENT))
                "rect" -> {
                    val x = op.optDouble("x", 0.0).toFloat() * d
                    val y = op.optDouble("y", 0.0).toFloat() * d
                    val w = op.optDouble("width", 100.0).toFloat() * d
                    val h = op.optDouble("height", 100.0).toFloat() * d
                    val r = op.optDouble("radius", 0.0).toFloat() * d
                    if (r > 0f) canvas.drawRoundRect(x, y, x + w, y + h, r, r, paint)
                    else canvas.drawRect(x, y, x + w, y + h, paint)
                }
                "circle" -> canvas.drawCircle(
                    op.optDouble("x", 0.0).toFloat() * d,
                    op.optDouble("y", 0.0).toFloat() * d,
                    op.optDouble("radius", 10.0).toFloat() * d, paint)
                "line" -> canvas.drawLine(
                    op.optDouble("x1", 0.0).toFloat() * d, op.optDouble("y1", 0.0).toFloat() * d,
                    op.optDouble("x2", 0.0).toFloat() * d, op.optDouble("y2", 0.0).toFloat() * d, paint)
                "text" -> canvas.drawText(
                    op.optString("text", ""), op.optDouble("x", 0.0).toFloat() * d,
                    op.optDouble("y", 0.0).toFloat() * d, paint)
                "arc" -> canvas.drawArc(
                    op.optDouble("x", 0.0).toFloat() * d, op.optDouble("y", 0.0).toFloat() * d,
                    op.optDouble("x", 0.0).toFloat() * d + op.optDouble("width", 50.0).toFloat() * d,
                    op.optDouble("y", 0.0).toFloat() * d + op.optDouble("height", 50.0).toFloat() * d,
                    op.optDouble("startAngle", 0.0).toFloat(),
                    op.optDouble("sweepAngle", 90.0).toFloat(), paint.style != Paint.Style.STROKE)
            }
        }
    }

    private fun parseColor(s: String, fallback: Int): Int = try {
        if (s.startsWith("#")) Color.parseColor(s) else fallback
    } catch (_: Exception) { fallback }

    /** Animations (spec §2): fade / slide / scale over a view alpha/translation. */
    fun animate(type: String, durationMs: Int, from: Float, to: Float) {
        val target = this
        val anim = when (type.lowercase()) {
            "fade" -> ValueAnimator.ofFloat(from, to).apply { alpha = from }
            "slide" -> ValueAnimator.ofFloat(from, to).apply { translationX = from * 100f }
            "scale" -> ValueAnimator.ofFloat(from, to).apply { scaleX = from; scaleY = from }
            else -> return
        }
        anim.duration = durationMs.coerceIn(50, 30000).toLong()
        anim.addUpdateListener {
            val v = it.animatedValue as Float
            when (type.lowercase()) {
                "fade" -> target.alpha = v
                "slide" -> target.translationX = v * 100f
                "scale" -> { target.scaleX = v; target.scaleY = v }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) anim.start()
    }
}
