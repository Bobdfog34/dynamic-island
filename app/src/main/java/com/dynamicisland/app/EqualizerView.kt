package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

class EqualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = 4
    private var phase = 0f
    private var running = false
    private var color1 = Color.parseColor("#0A84FF")
    private var color2 = Color.parseColor("#BF5AF2")

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 850
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColors(c1: Int, c2: Int) {
        color1 = c1
        color2 = c2
        invalidate()
    }

    fun startAnim() {
        if (!running) {
            running = true
            animator.start()
        }
    }

    fun stopAnim() {
        if (running) {
            running = false
            animator.cancel()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.16f
        val barW = (w - gap * (bars - 1)) / bars
        val radius = barW / 2f
        for (i in 0 until bars) {
            val t = abs(sin(phase + i * 1.05f))
            val bh = h * (0.30f + 0.70f * t)
            val left = i * (barW + gap)
            val top = (h - bh) / 2f
            rect.set(left, top, left + barW, top + bh)
            paint.color = blend(color1, color2, i.toFloat() / (bars - 1))
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * f).toInt(),
            (ag + (bg - ag) * f).toInt(),
            (ab + (bb - ab) * f).toInt()
        )
    }
}
