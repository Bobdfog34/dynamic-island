package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

class ProgressLineView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f
    private var from = 0f
    private var animator: ValueAnimator? = null
    private var c1 = Color.parseColor("#0A84FF")
    private var c2 = Color.parseColor("#BF5AF2")

    init {
        trackPaint.color = Color.parseColor("#33FFFFFF")
    }

    fun setColors(color1: Int, color2: Int) {
        c1 = color1
        c2 = color2
        invalidate()
    }

    fun setFraction(value: Float, animate: Boolean) {
        val target = value.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            fraction = target
            invalidate()
            return
        }
        from = fraction
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = from + (target - from) * it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val fillW = w * fraction
        if (fillW <= 1f) return
        fillPaint.shader = LinearGradient(
            0f, 0f, w, 0f, c1, c2, Shader.TileMode.CLAMP
        )
        rect.set(0f, 0f, fillW, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)
    }
}
