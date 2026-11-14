package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

class IslandView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, COMPACT, EXPANDED }

    interface Listener {
        fun onToggled(expanded: Boolean)
        fun onMoved(dx: Int, dy: Int)
        fun onLongPress()
    }

    var listener: Listener? = null
    var prefs: Prefs = Prefs(context)

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val screenWidth = resources.displayMetrics.widthPixels

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private val pill = LinearLayout(context)
    private val leftIcon = ImageView(context)
    private val rightIcon = ImageView(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val textBox = LinearLayout(context)
    private val background = GradientDrawable()

    private var state = State.IDLE
    private var animator: ValueAnimator? = null
    private var currentContent: IslandContent? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTap = 0L

    private val collapseRunnable = Runnable {
        if (state == State.COMPACT) setIdle()
    }

    init {
        background.shape = GradientDrawable.RECTANGLE
        background.setColor(prefs.bgColor)
        background.cornerRadius = dpf(prefs.cornerRadius.toFloat())
        background.setStroke(dp(1), Color.parseColor("#22FFFFFF"))

        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = background
        pill.setPadding(dp(10), dp(6), dp(10), dp(6))

        leftIcon.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        leftIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        leftIcon.visibility = View.GONE

        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END

        subtitleView.setTextColor(Color.parseColor("#B0FFFFFF"))
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        subtitleView.maxLines = 1
        subtitleView.ellipsize = android.text.TextUtils.TruncateAt.END

        textBox.orientation = LinearLayout.VERTICAL
        textBox.setPadding(dp(8), 0, dp(8), 0)
        textBox.addView(titleView)
        textBox.addView(subtitleView)
        val tbLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textBox.layoutParams = tbLp

        rightIcon.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        rightIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        rightIcon.visibility = View.GONE

        pill.addView(leftIcon)
        pill.addView(textBox)
        pill.addView(rightIcon)

        val pillLp = FrameLayout.LayoutParams(
            dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), Gravity.CENTER
        )
        pill.layoutParams = pillLp
        addView(pill)

        isClickable = true
        setOnTouchListener { _, event -> handleTouch(event) }

        applyAppearance()
    }

    fun applyAppearance() {
        background.setColor(prefs.bgColor)
        background.alpha = prefs.bgAlpha
        background.cornerRadius = dpf(prefs.cornerRadius.toFloat())
        if (prefs.borderEnabled) {
            background.setStroke(dp(1), Color.parseColor("#22FFFFFF"))
        } else {
            background.setStroke(0, Color.TRANSPARENT)
        }
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        if (state == State.IDLE) {
            lp.width = dp(prefs.collapsedWidth)
            lp.height = dp(prefs.collapsedHeight)
            pill.layoutParams = lp
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    handler.removeCallbacks(collapseRunnable)
                }
                if (dragging) {
                    listener?.onMoved(dx.toInt(), dy.toInt())
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        listener?.onLongPress()
                    } else {
                        toggle()
                    }
                    lastTap = now
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    fun state(): State = state

    fun toggle() {
        if (state == State.EXPANDED) setIdle() else expand()
    }

    fun expand() {
        if (currentContent == null) return
        handler.removeCallbacks(collapseRunnable)
        setState(State.EXPANDED)
        listener?.onToggled(true)
    }

    fun setIdle() {
        handler.removeCallbacks(collapseRunnable)
        setState(State.IDLE)
        listener?.onToggled(false)
    }

    fun showContent(content: IslandContent) {
        currentContent = content
        titleView.text = content.title
        subtitleView.text = content.subtitle

        if (content.icon != null) {
            leftIcon.setImageDrawable(content.icon)
            leftIcon.visibility = View.VISIBLE
        } else {
            leftIcon.setImageDrawable(null)
            leftIcon.visibility = View.GONE
        }

        val prefix = if (content.emoji.isNotEmpty()) content.emoji + " " else ""
        titleView.text = prefix + content.title

        if (state != State.EXPANDED) {
            setState(State.COMPACT)
            handler.removeCallbacks(collapseRunnable)
            if (prefs.autoCollapseMs > 0) {
                handler.postDelayed(collapseRunnable, prefs.autoCollapseMs.toLong())
            }
        }
    }

    fun showPersistent(content: IslandContent) {
        currentContent = content
        val prefix = if (content.emoji.isNotEmpty()) content.emoji + " " else ""
        titleView.text = prefix + content.title
        subtitleView.text = content.subtitle
        if (content.icon != null) {
            leftIcon.setImageDrawable(content.icon)
            leftIcon.visibility = View.VISIBLE
        }
        if (state != State.EXPANDED) setState(State.COMPACT)
    }

    fun clear() {
        currentContent = null
        setIdle()
    }

    private fun setState(newState: State) {
        state = newState
        when (newState) {
            State.IDLE -> {
                titleView.visibility = View.GONE
                subtitleView.visibility = View.GONE
                leftIcon.visibility = View.GONE
                rightIcon.visibility = View.GONE
                animateTo(dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), idle = true)
            }
            State.COMPACT -> {
                leftIcon.visibility = if (leftIcon.drawable != null) View.VISIBLE else View.GONE
                titleView.visibility = View.VISIBLE
                subtitleView.visibility = View.GONE
                rightIcon.visibility = View.GONE
                val w = min((screenWidth - dp(48)), dp(230))
                animateTo(w, dp(40), idle = false)
            }
            State.EXPANDED -> {
                leftIcon.visibility = if (leftIcon.drawable != null) View.VISIBLE else View.GONE
                titleView.visibility = View.VISIBLE
                subtitleView.visibility = View.VISIBLE
                rightIcon.visibility = View.GONE
                val w = min((screenWidth - dp(24)), dp(380))
                animateTo(w, dp(84), idle = false)
            }
        }
    }

    private fun animateTo(targetW: Int, targetH: Int, idle: Boolean) {
        animator?.cancel()
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        val startW = if (lp.width > 0) lp.width else dp(prefs.collapsedWidth)
        val startH = if (lp.height > 0) lp.height else dp(prefs.collapsedHeight)

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = prefs.animDuration.toLong()
            interpolator = if (idle) DecelerateInterpolator() else OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedFraction
                val w = (startW + (targetW - startW) * f).toInt()
                val h = (startH + (targetH - startH) * f).toInt()
                val p = pill.layoutParams as FrameLayout.LayoutParams
                p.width = w
                p.height = h
                pill.layoutParams = p
                val radius = min(dpf(prefs.cornerRadius.toFloat()), h / 2f)
                background.cornerRadius = radius
            }
            start()
        }
    }
}
