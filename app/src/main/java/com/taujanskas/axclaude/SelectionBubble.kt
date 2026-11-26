package com.taujanskas.axclaude

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The floating "✳ Ax Claude" bubble shown next to the browser's text-selection menu.
 * Uses an accessibility overlay, so it needs no extra permission.
 */
class SelectionBubble(
    private val service: AccessibilityService,
    private val onTap: () -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    private fun dp(v: Int) = (v * density).toInt()

    private fun createView(): TextView = TextView(service).apply {
        text = "✳ Ax Claude"
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xFFD97757.toInt())
        }
        elevation = dp(6).toFloat()
        setOnClickListener { onTap() }
    }

    /** Shows (or moves) the bubble beside [menu], the bounds of the selection menu. */
    fun showNear(menu: Rect) {
        val v = view ?: createView()
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = v.measuredWidth
        val h = v.measuredHeight
        val screen = screenSize()
        val gap = dp(8)

        // Prefer the right of the menu, then above it, then below it.
        var x: Int
        var y: Int
        if (menu.right + gap + w <= screen.x - gap) {
            x = menu.right + gap
            y = menu.centerY() - h / 2
        } else if (menu.top - gap - h >= dp(40)) {
            x = menu.right - w
            y = menu.top - gap - h
        } else {
            x = menu.right - w
            y = menu.bottom + gap
        }
        x = x.coerceIn(gap, (screen.x - w - gap).coerceAtLeast(gap))
        y = y.coerceIn(gap, (screen.y - h - gap).coerceAtLeast(gap))

        val p = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        p.x = x
        p.y = y

        try {
            if (view == null) {
                wm.addView(v, p)
                view = v
                params = p
            } else {
                wm.updateViewLayout(v, p)
            }
        } catch (e: Exception) {
            view = null
            params = null
        }
    }

    fun hide() {
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        view = null
        params = null
    }

    private fun screenSize(): android.graphics.Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            val m = service.resources.displayMetrics
            android.graphics.Point(m.widthPixels, m.heightPixels)
        }
    }
}
