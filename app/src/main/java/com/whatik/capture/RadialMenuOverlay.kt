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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Menu circolare attorno alla bolla (come la S Pen): azioni disposte su un arco, dal lato
 * dove c'è spazio, su uno sfondo trasparente che si chiude toccando altrove.
 */
class RadialMenuOverlay(
    private val context: Context,
    private val anchorX: Int,
    private val anchorY: Int,
    private val items: List<Item>,
    private val onDismiss: () -> Unit,
) {
    class Item(val iconRes: Int, val label: String, val color: Int, val onClick: () -> Unit)

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
        root.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) { v.performClick(); onDismiss() }
            true
        }
        val metrics = context.resources.displayMetrics
        val radius = dp(96)
        val size = dp(56)
        // arco verso destra se la bolla e' a sinistra, altrimenti verso sinistra
        val toRight = anchorX < metrics.widthPixels / 2
        val baseAngle = if (toRight) 0.0 else Math.PI
        val spread = Math.toRadians(55.0)
        val count = items.size
        items.forEachIndexed { i, item ->
            val offset = (i - (count - 1) / 2.0) * spread
            val angle = baseAngle + (if (toRight) offset else -offset)
            val cx = anchorX + radius * cos(angle)
            val cy = anchorY + radius * sin(angle)
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val button = ImageView(context).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.WHITE)
                val pad = dp(14)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.color) }
                elevation = dp(6).toFloat()
                setOnClickListener { item.onClick() }
                contentDescription = item.label
            }
            column.addView(button, LinearLayout.LayoutParams(size, size))
            val label = TextView(context).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xCC000000.toInt()) }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            column.addView(label, lp)
            val columnWidth = dp(96)
            val flp = FrameLayout.LayoutParams(columnWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            flp.leftMargin = (cx - columnWidth / 2).toInt().coerceIn(0, metrics.widthPixels - columnWidth)
            flp.topMargin = (cy - size / 2).toInt().coerceIn(0, metrics.heightPixels - dp(100))
            root.addView(column, flp)
        }
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

    @Suppress("unused")
    private fun View.noop() = Unit
}
