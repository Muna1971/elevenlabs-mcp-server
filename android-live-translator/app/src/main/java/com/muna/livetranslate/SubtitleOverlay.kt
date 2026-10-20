package com.muna.livetranslate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Floating, draggable subtitle bar drawn on top of every app. Main thread only. */
class SubtitleOverlay(private val context: Context, textSizeSp: Int) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { box.visibility = View.GONE }

    private val previousLine = subtitleText(textSizeSp * 0.8f).apply {
        alpha = 0.7f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    // Never cover the video controls: show at most 3 lines.
    private val currentLine = subtitleText(textSizeSp.toFloat()).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(12)
        setPadding(pad, dp(8), pad, dp(8))
        background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = dp(14).toFloat()
        }
        addView(previousLine)
        addView(currentLine)
        visibility = View.GONE
    }

    private val root = FrameLayout(context).apply {
        setPadding(dp(12), 0, dp(12), 0)
        addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(90)
    }

    private var attached = false
    private var lastFinal = ""

    fun show() {
        if (attached) return
        enableDragging()
        wm.addView(root, params)
        attached = true
    }

    fun remove() {
        handler.removeCallbacks(hide)
        if (attached) wm.removeView(root)
        attached = false
    }

    /** Removes all subtitles, e.g. when a new video starts. */
    fun clear() {
        lastFinal = ""
        handler.removeCallbacks(hide)
        box.visibility = View.GONE
    }

    /** Short informational message, e.g. "ready". */
    fun showStatus(message: String) = render(previous = "", current = message)

    /** Finished translation of a full sentence. */
    fun showFinal(arabic: String) {
        if (arabic.isBlank()) return
        val previous = lastFinal
        lastFinal = arabic
        render(previous = previous, current = arabic)
    }

    private fun render(previous: String, current: String) {
        previousLine.text = previous
        previousLine.visibility = if (previous.isBlank()) View.GONE else View.VISIBLE
        currentLine.text = current
        box.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, HIDE_AFTER_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging() {
        var startY = 0
        var startRawY = 0f
        box.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    startRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gravity is BOTTOM, so a larger y moves the bar up.
                    params.y = (startY - (event.rawY - startRawY)).toInt().coerceAtLeast(0)
                    if (attached) wm.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun subtitleText(sizeSp: Float) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_RTL
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        setLineSpacing(0f, 1.1f)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val HIDE_AFTER_MS = 7_000L
    }
}
