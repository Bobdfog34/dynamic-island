package com.dynamicisland.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View

class RingProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#33FFFFFF")
    }
    private val progPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private var progress = 0f
    private var text = ""
    private var c1 = Color.parseColor("#0A84FF")
    private var c2 = Color.parseColor("#BF5AF2")

    fun setColors(color1: Int, color2: Int) {
        c1 = color1
        c2 = color2
        invalidate()
    }

    fun setProgress(value: Float, label: String = "") {
        progress = value.coerceIn(0f, 1f)
        text = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val stroke = h * 0.14f
        trackPaint.strokeWidth = stroke
        progPaint.strokeWidth = stroke
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(cx, cy) - stroke / 2f - 1f

        canvas.drawCircle(cx, cy, r, trackPaint)

        progPaint.shader = SweepGradient(cx, cy, c1, c2)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, progPaint)
        }
    }
}
