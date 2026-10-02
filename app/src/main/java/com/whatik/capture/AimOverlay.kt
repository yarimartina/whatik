package com.whatik.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.whatik.R
import kotlin.math.abs

/**
 * Mirino "punta e cattura": schermo trasparente, un tocco sullo sticker in TikTok lo cattura.
 * L'unico elemento visibile è una striscia in alto con l'istruzione e "Annulla", così non
 * copre le file di sticker.
 */
class AimOverlay(
    private val context: Context,
    private val onPoint: (x: Float, y: Float) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = FrameLayout(context)
    private var attached = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        root.setOnTouchListener(TapListener())
        val banner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xE6000000.toInt()) }
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.aim_hint)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        banner.addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = TextView(context).apply {
            text = context.getString(R.string.aim_cancel)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF424242.toInt()) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onCancel() }
        }
        banner.addView(cancel)
        root.addView(
            banner,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                topMargin = dp(40)
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
    }

    fun hide() {
        if (!attached) return
        windowManager.removeView(root)
        attached = false
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Un tocco (senza trascinamento) sullo sfondo = punto da catturare. */
    private inner class TapListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; moved = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > dp(12) || abs(event.rawY - downY) > dp(12)) moved = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performClick(); onPoint(event.rawX, event.rawY) }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}
