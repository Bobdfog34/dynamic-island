package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class BatteryView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var level = 0.5f
    private var pulse = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedFraction
            invalidate()
        }
    }

    fun setLevel(percent: Int) {
        level = (percent / 100f).coerceIn(0f, 1f)
        invalidate()
    }

    fun startAnim() {
        if (!animator.isStarted) animator.start()
    }

    fun stopAnim() {
        animator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = h * 0.16f
        val capW = w * 0.09f
        val bodyRight = w - capW - pad * 0.6f
        val radius = h * 0.28f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.13f
        paint.color = Color.parseColor("#55FFFFFF")
        rect.set(pad, pad, bodyRight, h - pad)
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#66FFFFFF")
        val capTop = h * 0.34f
        val capBottom = h * 0.66f
        rect.set(bodyRight, capTop, w - pad * 0.2f, capBottom)
        canvas.drawRoundRect(rect, radius * 0.6f, radius * 0.6f, paint)

        val inset = paint.strokeWidth * 0.5f + h * 0.05f
        val fillRight = pad + (bodyRight - pad - inset) * level + inset
        val green = Color.parseColor("#30D158")
        val alpha = (170 + 85 * pulse).toInt().coerceIn(0, 255)
        paint.color = Color.argb(alpha, Color.red(green), Color.green(green), Color.blue(green))
        rect.set(pad + inset, pad + inset, fillRight, h - pad - inset)
        if (fillRight > pad + inset) {
            canvas.drawRoundRect(rect, radius * 0.7f, radius * 0.7f, paint)
        }
    }
}
