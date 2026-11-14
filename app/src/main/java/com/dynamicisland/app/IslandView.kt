package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        fun onMoved(dx: Int, dy: Int)
        fun onLongPress()
        fun onIdle()
    }

    var listener: Listener? = null
    var prefs: Prefs = Prefs(context)

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val screenWidth = resources.displayMetrics.widthPixels

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private val pill = LinearLayout(context)
    private val leftContainer = FrameLayout(context)
    private val leftIcon = ImageView(context)
    private val emojiBadge = TextView(context)
    private val rightContainer = FrameLayout(context)
    private val equalizer = EqualizerView(context)
    private val battery = BatteryView(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val textBox = LinearLayout(context)
    private val idleDot = View(context)
    private val background = GradientDrawable()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowRect = RectF()

    private var state = State.IDLE
    private var sizeAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null
    private var current: IslandContent? = null
    private var lastPersistent: IslandContent? = null
    private val queue = ArrayDeque<IslandContent>()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTap = 0L

    private val collapseRunnable = Runnable { finishTransient() }

    init {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setWillNotDraw(false)

        background.shape = GradientDrawable.RECTANGLE
        background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        background.setStroke(dp(1), Color.parseColor("#33FFFFFF"))

        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = background
        pill.setPadding(dp(8), dp(6), dp(12), dp(6))
        pill.clipToOutline = true

        leftContainer.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        leftIcon.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        leftIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        emojiBadge.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        emojiBadge.gravity = Gravity.CENTER
        emojiBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        emojiBadge.setTextColor(Color.WHITE)
        leftContainer.addView(leftIcon)
        leftContainer.addView(emojiBadge)

        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END

        subtitleView.setTextColor(Color.parseColor("#B3FFFFFF"))
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        subtitleView.maxLines = 1
        subtitleView.ellipsize = android.text.TextUtils.TruncateAt.END

        textBox.orientation = LinearLayout.VERTICAL
        textBox.setPadding(dp(9), 0, dp(9), 0)
        textBox.addView(titleView)
        textBox.addView(subtitleView)
        textBox.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        rightContainer.layoutParams = LinearLayout.LayoutParams(dp(34), dp(20))
        equalizer.layoutParams = FrameLayout.LayoutParams(dp(26), dp(16), Gravity.CENTER)
        battery.layoutParams = FrameLayout.LayoutParams(dp(30), dp(15), Gravity.CENTER)
        rightContainer.addView(equalizer)
        rightContainer.addView(battery)

        idleDot.layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        idleDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#4D5A5A66"))
        }

        pill.addView(idleDot)
        pill.addView(leftContainer)
        pill.addView(textBox)
        pill.addView(rightContainer)

        val pillLp = FrameLayout.LayoutParams(
            dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), Gravity.CENTER
        )
        pill.layoutParams = pillLp
        addView(pill)

        isClickable = true
        setOnTouchListener { _, event -> handleTouch(event) }

        applyAppearance()
        startBreathing()
    }

    fun applyAppearance() {
        val bg = prefs.bgColor
        val top = blend(bg, Color.WHITE, 0.10f)
        val bottom = blend(bg, Color.BLACK, 0.06f)
        background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        background.colors = intArrayOf(top, bottom)
        background.alpha = prefs.bgAlpha
        background.cornerRadius = dpf(prefs.cornerRadius.toFloat())
        if (prefs.borderEnabled) {
            background.setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        } else {
            background.setStroke(0, Color.TRANSPARENT)
        }
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        if (state == State.IDLE) {
            lp.width = dp(prefs.collapsedWidth)
            lp.height = dp(prefs.collapsedHeight)
            pill.layoutParams = lp
        }
        invalidate()
    }

    private fun startBreathing() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val f = it.animatedFraction
                val s = 1f + 0.012f * f
                pill.scaleX = s
                pill.scaleY = s
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!prefs.glowEnabled) return
        val accent = if (prefs.bgColor == Color.parseColor("#0A84FF")) Color.parseColor("#0A84FF")
        else Color.parseColor("#0A84FF")
        val pad = dpf(9f)
        val r = (pill.height / 2f) + pad
        glowRect.set(
            pill.left - pad, pill.top - pad,
            pill.right + pad, pill.bottom + pad
        )
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(glowRect, r, r, glowPaint)
        val pad2 = dpf(4f)
        val r2 = (pill.height / 2f) + pad2
        glowRect.set(pill.left - pad2, pill.top - pad2, pill.right + pad2, pill.bottom + pad2)
        glowPaint.color = Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(glowRect, r2, r2, glowPaint)
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
        if (state == State.EXPANDED) finishTransient() else expand()
    }

    private fun expand() {
        if (current == null) return
        handler.removeCallbacks(collapseRunnable)
        setState(State.EXPANDED)
    }

    fun show(content: IslandContent) {
        if (content.type == IslandContent.Type.MUSIC || content.type == IslandContent.Type.TIMER) {
            showPersistent(content)
        } else {
            enqueue(content)
        }
    }

    fun enqueue(content: IslandContent) {
        if (state == State.EXPANDED || (current != null && !isPersistent(current))) {
            queue.addLast(content)
            return
        }
        showTransient(content)
    }

    private fun showPersistent(content: IslandContent) {
        lastPersistent = content
        current = content
        applyContent(content)
        if (state == State.EXPANDED) {
            updateContentOnly(content)
        } else {
            setState(State.COMPACT)
        }
    }

    private fun showTransient(content: IslandContent) {
        current = content
        applyContent(content)
        if (state != State.EXPANDED) {
            setState(State.COMPACT)
        }
        handler.removeCallbacks(collapseRunnable)
        if (prefs.autoCollapseMs > 0) {
            handler.postDelayed(collapseRunnable, prefs.autoCollapseMs.toLong())
        }
    }

    private fun isPersistent(c: IslandContent?): Boolean =
        c != null && (c.type == IslandContent.Type.MUSIC || c.type == IslandContent.Type.TIMER)

    private fun finishTransient() {
        handler.removeCallbacks(collapseRunnable)
        current = null
        if (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            setIdleImmediate()
            handler.postDelayed({ showTransient(next) }, 220)
        } else {
            setIdleImmediate()
            listener?.onIdle()
        }
    }

    fun clear() {
        queue.clear()
        current = null
        lastPersistent = null
        setIdleImmediate()
    }

    private fun setIdleImmediate() {
        setState(State.IDLE)
    }

    private fun updateContentOnly(content: IslandContent) {
        titleView.text = content.title
        subtitleView.text = content.subtitle
        applyBadge(content)
    }

    private fun applyContent(content: IslandContent) {
        updateContentOnly(content)
        applyRightWidget(content)
    }

    private fun applyBadge(content: IslandContent) {
        if (content.icon != null) {
            leftIcon.setImageDrawable(content.icon)
            leftIcon.visibility = View.VISIBLE
            emojiBadge.visibility = View.GONE
        } else if (content.emoji.isNotEmpty()) {
            leftIcon.setImageDrawable(null)
            leftIcon.visibility = View.GONE
            emojiBadge.text = content.emoji
            emojiBadge.visibility = View.VISIBLE
        } else {
            leftIcon.visibility = View.GONE
            emojiBadge.visibility = View.GONE
        }
    }

    private fun applyRightWidget(content: IslandContent) {
        when (content.type) {
            IslandContent.Type.MUSIC -> {
                equalizer.visibility = View.VISIBLE
                battery.visibility = View.GONE
                battery.stopAnim()
                equalizer.startAnim()
            }
            IslandContent.Type.CHARGING, IslandContent.Type.BATTERY_LOW -> {
                battery.visibility = View.VISIBLE
                equalizer.visibility = View.GONE
                equalizer.stopAnim()
                battery.setLevel(if (content.progress >= 0) content.progress else 50)
                battery.startAnim()
            }
            else -> {
                equalizer.visibility = View.GONE
                battery.visibility = View.GONE
                equalizer.stopAnim()
                battery.stopAnim()
            }
        }
    }

    private fun setState(newState: State) {
        state = newState
        when (newState) {
            State.IDLE -> {
                idleDot.visibility = View.VISIBLE
                leftContainer.visibility = View.GONE
                textBox.visibility = View.GONE
                rightContainer.visibility = View.GONE
                equalizer.stopAnim()
                battery.stopAnim()
                animateTo(dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), idle = true)
            }
            State.COMPACT -> {
                idleDot.visibility = View.GONE
                leftContainer.visibility =
                    if (leftIcon.drawable != null || emojiBadge.text.isNotEmpty()) View.VISIBLE else View.GONE
                textBox.visibility = View.VISIBLE
                subtitleView.visibility = View.GONE
                rightContainer.visibility =
                    if (equalizer.visibility == View.VISIBLE || battery.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                val w = min((screenWidth - dp(48)), dp(240))
                animateTo(w, dp(42), idle = false)
                bounceContent()
            }
            State.EXPANDED -> {
                idleDot.visibility = View.GONE
                leftContainer.visibility =
                    if (leftIcon.drawable != null || emojiBadge.text.isNotEmpty()) View.VISIBLE else View.GONE
                textBox.visibility = View.VISIBLE
                subtitleView.visibility = View.VISIBLE
                rightContainer.visibility =
                    if (equalizer.visibility == View.VISIBLE || battery.visibility == View.VISIBLE) View.VISIBLE else View.GONE
                val w = min((screenWidth - dp(24)), dp(384))
                animateTo(w, dp(86), idle = false)
                subtitleView.alpha = 0f
                subtitleView.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    private fun bounceContent() {
        leftContainer.scaleX = 0.6f
        leftContainer.scaleY = 0.6f
        leftContainer.animate().scaleX(1f).scaleY(1f)
            .setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun animateTo(targetW: Int, targetH: Int, idle: Boolean) {
        sizeAnimator?.cancel()
        val lp = pill.layoutParams as FrameLayout.LayoutParams
        val startW = if (lp.width > 0) lp.width else dp(prefs.collapsedWidth)
        val startH = if (lp.height > 0) lp.height else dp(prefs.collapsedHeight)

        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = prefs.animDuration.toLong()
            interpolator = if (idle) DecelerateInterpolator() else OvershootInterpolator(0.85f)
            addUpdateListener {
                val f = it.animatedFraction
                val w = (startW + (targetW - startW) * f).toInt()
                val h = (startH + (targetH - startH) * f).toInt()
                val p = pill.layoutParams as FrameLayout.LayoutParams
                p.width = w
                p.height = h
                pill.layoutParams = p
                background.cornerRadius = min(dpf(prefs.cornerRadius.toFloat()), h / 2f)
                invalidate()
            }
            start()
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * f).toInt().coerceIn(0, 255),
            (ag + (bg - ag) * f).toInt().coerceIn(0, 255),
            (ab + (bb - ab) * f).toInt().coerceIn(0, 255)
        )
    }
}
