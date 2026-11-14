package com.dynamicisland.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min

class IslandView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, COMPACT, EXPANDED }

    interface Listener {
        fun onMoved(dx: Int, dy: Int)
        fun onExpandToggle()
        fun onTap()
        fun onIdle()
        fun onMediaAction(action: Int)
    }

    companion object {
        const val MEDIA_PREV = 0
        const val MEDIA_PLAY_PAUSE = 1
        const val MEDIA_NEXT = 2
        const val MAX_LINES = 5
    }

    var listener: Listener? = null
    var prefs: Prefs = Prefs(context)

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val screenWidth = resources.displayMetrics.widthPixels

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private val pill = FrameLayout(context)
    private val sheen = View(context)
    private val contentRow = LinearLayout(context)

    private val idleDot = View(context)

    private val leftContainer = FrameLayout(context)
    private val leftIcon = ImageView(context)
    private val emojiBadge = TextView(context)
    private val albumArt = ImageView(context)

    private val textBox = LinearLayout(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val progressLine = ProgressLineView(context)
    private val linesBox = LinearLayout(context)
    private val lineViews = ArrayList<TextView>()

    private val spacer = View(context)

    private val rightContainer = FrameLayout(context)
    private val equalizer = EqualizerView(context)
    private val battery = BatteryView(context)
    private val ring = RingProgressView(context)

    private val controls = LinearLayout(context)
    private val prevBtn = ImageButton(context)
    private val playBtn = ImageButton(context)
    private val nextBtn = ImageButton(context)

    private val background = GradientDrawable()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowRect = RectF()

    private var state = State.IDLE
    private var sizeAnimator: ValueAnimator? = null
    private var current: IslandContent? = null
    private var lastPersistent: IslandContent? = null
    private val queue = ArrayDeque<IslandContent>()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressRunnable = Runnable {
        longFired = true
        listener?.onExpandToggle()
    }

    private val collapseRunnable = Runnable { finishTransient() }

    init {
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setWillNotDraw(false)

        background.shape = GradientDrawable.RECTANGLE
        background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        background.setStroke(0, Color.TRANSPARENT)

        pill.clipToOutline = true
        pill.background = background

        sheen.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        sheen.background = ContextCompat.getDrawable(context, R.drawable.island_sheen)
        sheen.visibility = View.GONE
        pill.addView(sheen)

        contentRow.orientation = LinearLayout.HORIZONTAL
        contentRow.gravity = Gravity.CENTER
        contentRow.setPadding(dp(10), dp(4), dp(10), dp(4))
        contentRow.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pill.addView(contentRow)

        idleDot.layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        idleDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#3D5A5A66"))
        }
        contentRow.addView(idleDot)

        leftContainer.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        leftIcon.layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        leftIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        emojiBadge.layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        emojiBadge.gravity = Gravity.CENTER
        emojiBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        emojiBadge.setTextColor(Color.WHITE)
        albumArt.layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        albumArt.scaleType = ImageView.ScaleType.CENTER_CROP
        albumArt.clipToOutline = true
        albumArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpf(10f))
            }
        }
        leftContainer.addView(albumArt)
        leftContainer.addView(leftIcon)
        leftContainer.addView(emojiBadge)
        contentRow.addView(leftContainer)

        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END

        subtitleView.setTextColor(Color.parseColor("#B3FFFFFF"))
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        subtitleView.maxLines = 1
        subtitleView.ellipsize = android.text.TextUtils.TruncateAt.END

        textBox.orientation = LinearLayout.VERTICAL
        textBox.gravity = Gravity.CENTER_VERTICAL
        textBox.setPadding(dp(10), 0, dp(10), 0)
        textBox.addView(titleView)
        textBox.addView(subtitleView)
        val progLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
        )
        progLp.topMargin = dp(6)
        progressLine.layoutParams = progLp
        progressLine.visibility = View.GONE
        textBox.addView(progressLine)

        linesBox.orientation = LinearLayout.VERTICAL
        linesBox.visibility = View.GONE
        linesBox.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        for (i in 0 until MAX_LINES) {
            val tv = TextView(context)
            tv.setTextColor(Color.parseColor("#E6FFFFFF"))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setPadding(0, dp(1), 0, dp(1))
            tv.visibility = View.GONE
            linesBox.addView(tv)
            lineViews.add(tv)
        }
        textBox.addView(linesBox)

        textBox.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        contentRow.addView(textBox)

        spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        spacer.visibility = View.GONE
        contentRow.addView(spacer)

        rightContainer.layoutParams = LinearLayout.LayoutParams(dp(36), dp(24))
        equalizer.layoutParams = FrameLayout.LayoutParams(dp(30), dp(18), Gravity.CENTER)
        battery.layoutParams = FrameLayout.LayoutParams(dp(34), dp(17), Gravity.CENTER)
        ring.layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        rightContainer.addView(equalizer)
        rightContainer.addView(battery)
        rightContainer.addView(ring)
        contentRow.addView(rightContainer)

        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.visibility = View.GONE
        controls.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        controls.addView(mediaButton(prevBtn, R.drawable.ic_media_prev, MEDIA_PREV))
        controls.addView(mediaButton(playBtn, R.drawable.ic_media_play, MEDIA_PLAY_PAUSE))
        controls.addView(mediaButton(nextBtn, R.drawable.ic_media_next, MEDIA_NEXT))
        contentRow.addView(controls)

        val pillLp = LayoutParams(dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), Gravity.CENTER)
        pill.layoutParams = pillLp
        addView(pill)

        isClickable = true
        setOnTouchListener { _, event -> handleTouch(event) }

        applyAppearance()
    }

    private fun mediaButton(btn: ImageButton, icon: Int, action: Int): ImageButton {
        btn.setImageResource(icon)
        btn.background = null
        btn.setColorFilter(Color.WHITE)
        btn.alpha = 0.92f
        btn.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        btn.scaleType = ImageView.ScaleType.FIT_CENTER
        btn.setPadding(dp(6), dp(6), dp(6), dp(6))
        btn.setOnClickListener { listener?.onMediaAction(action) }
        return btn
    }

    fun applyAppearance() {
        val bg = prefs.bgColor
        val top = blend(bg, Color.WHITE, 0.04f)
        val bottom = bg
        background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        background.colors = intArrayOf(top, bottom)
        background.alpha = prefs.bgAlpha
        background.cornerRadius = dpf(prefs.cornerRadius.toFloat())
        if (prefs.borderEnabled) {
            background.setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        } else {
            background.setStroke(0, Color.TRANSPARENT)
        }
        sheen.visibility = if (prefs.sheenEnabled) View.VISIBLE else View.GONE
        val lp = pill.layoutParams as LayoutParams
        if (state == State.IDLE) {
            lp.width = dp(prefs.collapsedWidth)
            lp.height = dp(prefs.collapsedHeight)
            pill.layoutParams = lp
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!prefs.glowEnabled) return
        val accent = if (current?.type == IslandContent.Type.MUSIC) {
            Color.parseColor("#BF5AF2")
        } else {
            Color.parseColor("#0A84FF")
        }
        val pad = dpf(10f)
        val r = (pill.height / 2f) + pad
        glowRect.set(pill.left - pad, pill.top - pad, pill.right + pad, pill.bottom + pad)
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(glowRect, r, r, glowPaint)
        val pad2 = dpf(5f)
        val r2 = (pill.height / 2f) + pad2
        glowRect.set(pill.left - pad2, pill.top - pad2, pill.right + pad2, pill.bottom + pad2)
        glowPaint.color = Color.argb(44, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(glowRect, r2, r2, glowPaint)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longFired = false
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
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
                handler.removeCallbacks(longPressRunnable)
                if (!dragging && !longFired) {
                    if (state == State.EXPANDED) {
                        finishTransient()
                    } else if (current != null) {
                        listener?.onTap()
                    }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                dragging = false
                return true
            }
        }
        return true
    }

    fun state(): State = state

    fun currentType(): IslandContent.Type? = current?.type

    fun refresh(content: IslandContent) {
        current = content
        updateContentOnly(content)
        applyRightWidget(content)
    }

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

    fun showNotification(content: IslandContent) {
        val cur = current
        val sameKey = cur != null &&
                cur.type == IslandContent.Type.NOTIFICATION &&
                cur.key == content.key
        if (sameKey) {
            current = content
            updateContentOnly(content)
            applyRightWidget(content)
            setState(if (state == State.EXPANDED) State.EXPANDED else State.COMPACT)
            handler.removeCallbacks(collapseRunnable)
            if (prefs.autoCollapseMs > 0) {
                handler.postDelayed(collapseRunnable, prefs.autoCollapseMs.toLong())
            }
            return
        }
        enqueue(content)
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
        setState(if (state == State.EXPANDED) State.EXPANDED else State.COMPACT)
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
        if (content.type == IslandContent.Type.NOTIFICATION &&
            prefs.showAppLabel && content.appLabel.isNotBlank() && content.subtitle.isBlank()
        ) {
            subtitleView.text = content.appLabel
        }
        applyBadge(content)
        if (content.type == IslandContent.Type.MUSIC) {
            val f = content.progressFraction()
            if (f >= 0f) progressLine.setFraction(f, animate = false)
        }
        if (content.type == IslandContent.Type.TIMER) {
            val f = content.progressFraction()
            ring.setProgress(if (f >= 0f) f else 1f, content.subtitle)
        }
        playBtn.setImageResource(
            if (content.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        )
        for (i in lineViews.indices) {
            val tv = lineViews[i]
            if (content.type == IslandContent.Type.NOTIFICATION && i < content.lines.size) {
                tv.text = "•  " + content.lines[i]
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }
    }

    private fun applyContent(content: IslandContent) {
        updateContentOnly(content)
        applyRightWidget(content)
    }

    private fun applyBadge(content: IslandContent) {
        albumArt.visibility = View.GONE
        leftIcon.visibility = View.GONE
        emojiBadge.visibility = View.GONE
        emojiBadge.text = ""
        leftIcon.setImageDrawable(null)
        when {
            content.type == IslandContent.Type.MUSIC && content.albumArt != null -> {
                albumArt.setImageDrawable(content.albumArt)
                albumArt.visibility = View.VISIBLE
            }
            content.icon != null -> {
                leftIcon.setImageDrawable(content.icon)
                leftIcon.visibility = View.VISIBLE
            }
            content.emoji.isNotEmpty() -> {
                emojiBadge.text = content.emoji
                emojiBadge.visibility = View.VISIBLE
            }
        }
    }

    private fun applyRightWidget(content: IslandContent) {
        equalizer.visibility = View.GONE
        battery.visibility = View.GONE
        ring.visibility = View.GONE
        equalizer.stopAnim()
        battery.stopAnim()
        when (content.type) {
            IslandContent.Type.MUSIC -> {
                equalizer.visibility = View.VISIBLE
                equalizer.startAnim()
            }
            IslandContent.Type.CHARGING, IslandContent.Type.BATTERY_LOW -> {
                battery.visibility = View.VISIBLE
                battery.setLevel(if (content.progress >= 0) content.progress else 50)
                battery.startAnim()
            }
            IslandContent.Type.TIMER -> {
                ring.visibility = View.VISIBLE
                val f = content.progressFraction()
                ring.setProgress(if (f >= 0f) f else 1f)
            }
            else -> {
            }
        }
    }

    private fun setState(newState: State) {
        state = newState
        val type = current?.type
        val hasBadge = albumArt.visibility == View.VISIBLE ||
                leftIcon.drawable != null || emojiBadge.text.isNotEmpty()
        when (newState) {
            State.IDLE -> {
                idleDot.visibility = View.VISIBLE
                leftContainer.visibility = View.GONE
                textBox.visibility = View.GONE
                spacer.visibility = View.GONE
                rightContainer.visibility = View.GONE
                controls.visibility = View.GONE
                progressLine.visibility = View.GONE
                linesBox.visibility = View.GONE
                equalizer.stopAnim()
                battery.stopAnim()
                animateTo(dp(prefs.collapsedWidth), dp(prefs.collapsedHeight), idle = true)
            }
            State.COMPACT -> {
                idleDot.visibility = View.GONE
                val splitMusic = type == IslandContent.Type.MUSIC && hasBadge
                leftContainer.visibility = if (hasBadge) View.VISIBLE else View.GONE
                textBox.visibility = if (splitMusic) View.GONE else View.VISIBLE
                spacer.visibility = if (splitMusic) View.VISIBLE else View.GONE
                subtitleView.visibility = View.VISIBLE
                linesBox.visibility = View.GONE
                controls.visibility = View.GONE
                progressLine.visibility = View.GONE
                rightContainer.visibility =
                    if (type == IslandContent.Type.MUSIC || type == IslandContent.Type.CHARGING ||
                        type == IslandContent.Type.BATTERY_LOW || type == IslandContent.Type.TIMER
                    ) View.VISIBLE else View.GONE
                val w = if (splitMusic) min(screenWidth - dp(64), dp(216))
                else min(screenWidth - dp(48), dp(300))
                animateTo(w, dp(44), idle = false)
                bounceContent()
            }
            State.EXPANDED -> {
                idleDot.visibility = View.GONE
                leftContainer.visibility = if (hasBadge) View.VISIBLE else View.GONE
                textBox.visibility = View.VISIBLE
                spacer.visibility = View.GONE
                subtitleView.visibility = View.VISIBLE
                val music = type == IslandContent.Type.MUSIC
                val shownLines = if (type == IslandContent.Type.NOTIFICATION) {
                    min(current?.lines?.size ?: 0, MAX_LINES)
                } else 0
                linesBox.visibility = if (shownLines > 0) View.VISIBLE else View.GONE
                controls.visibility = if (music) View.VISIBLE else View.GONE
                progressLine.visibility =
                    if (music && (current?.progressFraction() ?: -1f) >= 0f) View.VISIBLE else View.GONE
                rightContainer.visibility =
                    if (music) View.GONE
                    else if (type == IslandContent.Type.CHARGING ||
                        type == IslandContent.Type.BATTERY_LOW || type == IslandContent.Type.TIMER
                    ) View.VISIBLE else View.GONE
                val w = min(screenWidth - dp(24), dp(408))
                val h = when {
                    music -> dp(112)
                    shownLines > 0 -> dp(50 + shownLines * 20)
                    type == IslandContent.Type.TIMER -> dp(96)
                    else -> dp(92)
                }
                animateTo(w, h, idle = false)
                subtitleView.alpha = 0f
                subtitleView.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    private fun bounceContent() {
        leftContainer.scaleX = 0.7f
        leftContainer.scaleY = 0.7f
        leftContainer.animate().scaleX(1f).scaleY(1f)
            .setDuration(300).setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun animateTo(targetW: Int, targetH: Int, idle: Boolean) {
        sizeAnimator?.cancel()
        val lp = pill.layoutParams as LayoutParams
        val startW = if (lp.width > 0) lp.width else dp(prefs.collapsedWidth)
        val startH = if (lp.height > 0) lp.height else dp(prefs.collapsedHeight)

        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = prefs.animDuration.toLong()
            interpolator = if (idle) DecelerateInterpolator() else OvershootInterpolator(0.75f)
            addUpdateListener {
                val f = it.animatedFraction
                val w = (startW + (targetW - startW) * f).toInt()
                val h = (startH + (targetH - startH) * f).toInt()
                val p = pill.layoutParams as LayoutParams
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
