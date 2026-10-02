package com.whatik.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowInsets
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.whatik.R

/**
 * Schermo bloccato durante la cattura: consuma ogni tocco (così non si scorre a metà
 * registrazione), mostra la zona inquadrata e un conto alla rovescia.
 *
 * In modalità "punta e cattura" i fotogrammi vengono ritagliati alla zona, quindi tutto
 * ciò che viene disegnato sta fuori dalla zona e non finisce nello sticker. In modalità
 * "tutti" si disegna solo una cornice sottile ai bordi, che il rilevatore ignora.
 */
class CaptureLockOverlay(
    private val context: Context,
    /** Zona inquadrata in pixel dello schermo; null = tutto lo schermo. */
    private val zone: Rect?,
    private val durationMs: Long,
    /** Tessere della griglia (pixel dello schermo), evidenziate una alla volta durante l'elaborazione. */
    private val tiles: List<Rect> = emptyList(),
    /** Sticker toccato con "Punta" (pixel dello schermo): resta in chiaro con la cornice attorno ai suoi bordi. */
    private val focus: Rect? = null,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = LockView()
    private var attached = false
    private var startedAt = 0L

    /** Dimensioni reali dello schermo: zona e tessere sono in queste coordinate. */
    private val screenSize: IntArray = DisplayMetrics().let { m ->
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
        intArrayOf(m.widthPixels, m.heightPixels)
    }

    /** Fase di elaborazione: indice della tessera corrente e testo; null = registrazione in corso. */
    @Volatile private var currentTile: Int? = null
    @Volatile private var phaseText: String? = null

    /** Passa alla fase di elaborazione evidenziando la tessera [index] (va chiamato sul thread principale). */
    fun showProcessing(index: Int, text: String) {
        currentTile = index
        phaseText = text
        view.invalidate()
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // la finestra deve coprire anche barra di stato (foro della fotocamera) e barra di navigazione,
        // altrimenti parte piu' in basso e tutto cio' che si disegna risulta spostato
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    fun show() {
        if (attached) return
        startedAt = System.currentTimeMillis()
        windowManager.addView(view, params)
        attached = true
        view.postInvalidateOnAnimation()
    }

    fun hide() {
        if (!attached) return
        windowManager.removeView(view)
        attached = false
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private inner class LockView : View(context) {
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f) }
        private val thin = Paint().apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
        private val done = Paint().apply { color = 0x5522AA55; style = Paint.Style.FILL }
        private val edge = Paint().apply { color = 0xFFFE2C55.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(4f) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(15f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val barBg = Paint().apply { color = 0x66FFFFFF }
        private val bar = Paint().apply { color = 0xFFFE2C55.toInt() }
        private val label = context.getString(R.string.lock_label)

        init { isClickable = true }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true // consuma tutto: lo schermo e' bloccato
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // si disegna in coordinate assolute dello schermo: se la finestra non parte dall'angolo
            // (barre di sistema), la differenza viene compensata qui
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            val w = screenSize[0].toFloat()
            val h = screenSize[1].toFloat()
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            val remaining = ((durationMs - elapsed) / 1000f).coerceAtLeast(0f)
            val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            val z = zone
            val current = currentTile
            if (current != null) {
                // elaborazione: tutte le tessere con cornice sottile, quella corrente in evidenza
                canvas.drawRect(0f, 0f, w, h, dim)
                tiles.forEachIndexed { i, tile ->
                    val paint = if (i == current) frame else thin
                    canvas.drawRoundRect(RectF(tile), dp(8f), dp(8f), paint)
                    if (i < current) canvas.drawRoundRect(RectF(tile), dp(8f), dp(8f), done)
                }
                // etichetta sopra l'area delle tessere (dove c'e' il video oscurato), altrimenti sotto
                val area = z ?: tiles.getOrNull(current)
                val textY = when {
                    area == null -> h / 2
                    area.top > h * 0.2f -> area.top - dp(24f)
                    else -> area.bottom + dp(32f)
                }
                canvas.drawText(phaseText ?: "", w / 2f, textY, text)
                return
            }
            if (z != null) {
                // resta in chiaro lo sticker toccato, se riconosciuto, altrimenti tutta la zona.
                // Cornice e ombra stanno appena fuori dallo sticker: non entrano nel suo ritaglio.
                val gap = if (focus != null) dp(5f) else 0f
                val hole = focus ?: z
                val hl = hole.left - gap
                val ht = hole.top - gap
                val hr = hole.right + gap
                val hb = hole.bottom + gap
                canvas.drawRect(0f, 0f, w, ht, dim)
                canvas.drawRect(0f, hb, w, h, dim)
                canvas.drawRect(0f, ht, hl, hb, dim)
                canvas.drawRect(hr, ht, w, hb, dim)
                val inset = dp(4f)
                canvas.drawRoundRect(RectF(hl - inset, ht - inset, hr + inset, hb + inset), dp(10f), dp(10f), frame)
                // etichetta e barra sopra o sotto la zona, dove c'e' spazio
                val above = z.top > h * 0.25f
                val textY = if (above) z.top - dp(36f) else z.bottom + dp(44f)
                canvas.drawText("$label · ${"%.1f".format(remaining)} s", w / 2f, textY, text)
                val barY = if (above) z.top - dp(20f) else z.bottom + dp(60f)
                drawBar(canvas, w * 0.15f, barY, w * 0.7f, progress)
            } else {
                val m = dp(2f)
                canvas.drawRect(m, m, w - m, h - m, edge)
                // etichetta nella fascia alta e barra in quella bassa: il rilevatore le ignora
                canvas.drawText("$label · ${"%.1f".format(remaining)} s", w / 2f, h * 0.045f, text)
                drawBar(canvas, w * 0.15f, h - dp(10f), w * 0.7f, progress)
            }
            if (elapsed < durationMs + 1000) postInvalidateOnAnimation()
        }

        private fun drawBar(canvas: Canvas, x: Float, y: Float, width: Float, progress: Float) {
            val hgt = dp(6f)
            canvas.drawRoundRect(RectF(x, y, x + width, y + hgt), hgt, hgt, barBg)
            canvas.drawRoundRect(RectF(x, y, x + width * progress, y + hgt), hgt, hgt, bar)
        }
    }
}
