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
 * Mirino a schermo intero mostrato dopo il tocco sulla bolla: un tocco sullo sticker in TikTok
 * avvia la cattura "punta e cattura"; in basso i pulsanti per catturare tutto ciò che si muove
 * o annullare. Viene rimosso prima che parta la cattura, così non finisce nei fotogrammi.
 */
class AimOverlay(
    private val context: Context,
    private val onPoint: (x: Float, y: Float) -> Unit,
    private val onCaptureAll: () -> Unit,
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
        root.setBackgroundColor(0x33000000)
        root.setOnTouchListener(TapListener())

        val hint = TextView(context).apply {
            text = context.getString(R.string.aim_hint)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = pill(0xCC000000.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(96)
            },
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button(context.getString(R.string.aim_all), 0xFFFE2C55.toInt()) { onCaptureAll() })
        buttons.addView(button(context.getString(R.string.aim_cancel), 0xCC424242.toInt()) { onCancel() })
        root.addView(
            buttons,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(120)
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

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        background = pill(color)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(6), 0, dp(6), 0)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Un tocco (senza trascinamento) sullo sfondo = punto da catturare. */
    private inner class TapListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > dp(12) || abs(event.rawY - downY) > dp(12)) moved = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        onPoint(event.rawX, event.rawY)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}
