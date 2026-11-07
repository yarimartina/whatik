package com.whatik.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.whatik.R
import kotlin.math.abs

/**
 * Bolla flottante mostrata sopra TikTok durante una sessione di cattura.
 * Tocco = cattura, pressione lunga = richiesta di conferma per terminare, trascinamento = sposta.
 */
class BubbleOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    enum class State { IDLE, RECORDING, PROCESSING, RESULT }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = dp(56)
    private val root = FrameLayout(context)
    private val icon = ImageView(context)
    private val badge = TextView(context)
    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(220)
    }
    private var attached = false

    init {
        root.background = background
        root.elevation = dp(6).toFloat()
        icon.setImageResource(R.drawable.ic_bubble)
        icon.setColorFilter(Color.WHITE)
        val pad = dp(14)
        icon.setPadding(pad, pad, pad, pad)
        root.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        badge.setTextColor(Color.WHITE)
        badge.setTypeface(null, Typeface.BOLD)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        badge.gravity = Gravity.CENTER
        badge.visibility = View.GONE
        root.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnTouchListener(DragTouchListener())
        setState(State.IDLE)
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
    }

    /** Centro della bolla in pixel dello schermo (per il menu circolare). */
    fun center(): IntArray = intArrayOf(params.x + sizePx / 2, params.y + sizePx / 2)

    fun hide() {
        if (!attached) return
        windowManager.removeView(root)
        attached = false
    }

    /** Nasconde la bolla senza rimuoverla: durante la cattura non deve finire nei fotogrammi. */
    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun setState(state: State, text: String? = null) {
        val color = when (state) {
            State.IDLE -> 0xFFFE2C55.toInt()
            State.RECORDING -> 0xFFD50000.toInt()
            State.PROCESSING -> 0xFF607D8B.toInt()
            State.RESULT -> 0xFF2E7D32.toInt()
        }
        background.setColor(color)
        if (text != null) {
            badge.text = text
            badge.visibility = View.VISIBLE
            icon.visibility = View.INVISIBLE
        } else {
            badge.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var originX = 0
        private var originY = 0
        private var moved = false
        private var downAt = 0L
        private val longPressRunnable = Runnable {
            if (!moved) {
                moved = true // evita che il rilascio conti come tocco
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                    downAt = System.currentTimeMillis()
                    root.postDelayed(longPressRunnable, 600)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        moved = true
                        root.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (originX + dx).toInt().coerceAtLeast(0)
                        params.y = (originY + dy).toInt().coerceAtLeast(0)
                        if (attached) windowManager.updateViewLayout(root, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    root.removeCallbacks(longPressRunnable)
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }
}

/**
 * Piccola conferma accanto alla bolla ("Terminare la sessione?" + "Termina"), in una finestra
 * a tutto schermo toccabile come il menu circolare: toccare altrove la chiude.
 */
class EndConfirmOverlay(
    private val context: Context,
    private val anchorX: Int,
    private val anchorY: Int,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit,
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
        root.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) { v.performClick(); onDismiss() }
            true
        }
        val metrics = context.resources.displayMetrics
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xEE222222.toInt()) }
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true // i tocchi sul pannello non lo chiudono
        }
        panel.addView(TextView(context).apply {
            text = context.getString(R.string.bubble_end_confirm)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        })
        val button = TextView(context).apply {
            text = context.getString(R.string.radial_end)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minimumWidth = dp(112)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xFFD50000.toInt()) }
            setPadding(dp(20), 0, dp(20), 0)
            isClickable = true
            setOnClickListener { onConfirm() }
        }
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(10)
        panel.addView(button, blp)
        // accanto alla bolla, dal lato dove c'e' spazio
        val panelWidth = dp(190)
        val toRight = anchorX < metrics.widthPixels / 2
        val flp = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        val left = if (toRight) anchorX + dp(40) else anchorX - dp(40) - panelWidth
        flp.leftMargin = left.coerceIn(0, (metrics.widthPixels - panelWidth).coerceAtLeast(0))
        flp.topMargin = (anchorY - dp(48)).coerceIn(0, (metrics.heightPixels - dp(150)).coerceAtLeast(0))
        root.addView(panel, flp)
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
}
