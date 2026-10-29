package com.whatik.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.whatik.R
import com.whatik.capture.AimOverlay
import com.whatik.capture.BubbleOverlay
import com.whatik.capture.CaptureLockOverlay
import com.whatik.capture.RadialMenuOverlay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli overlay della cattura (bolla, menu circolare, mirino, schermo bloccato) sono View classiche che il
 * CaptureService aggiunge al WindowManager. Qui non si usa il WindowManager: si costruiscono le stesse
 * classi, si prende per riflessione la loro View radice (campo privato, nessuna modifica al codice di
 * produzione), la si misura a schermo intero e la si disegna su una Bitmap 1080x2400 sopra un finto
 * schermo di TikTok. Gli overlay hanno colori fissi, quindi un solo tema (file overlay-*.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = Phone.LIGHT)
class OverlayScreenshotsTest {

    @get:Rule
    val phone = PhoneSetupRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.privateField(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    /** Schermo finto: video in alto e pannello sticker in basso, come in TikTok. */
    private fun screen(): Bitmap = FakeArt.screenFrame(Phone.WIDTH_PX, Phone.HEIGHT_PX)

    private fun Canvas.drawView(view: View, left: Int, top: Int, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        save()
        translate(left.toFloat(), top.toFloat())
        view.draw(this)
        restore()
    }

    private fun Canvas.drawFullScreen(view: View) = drawView(view, 0, 0, Phone.WIDTH_PX, Phone.HEIGHT_PX)

    private fun save(bitmap: Bitmap, name: String) {
        bitmap.captureRoboImage(ScreenshotOutput.file(name))
    }

    /** Bolla flottante nei suoi quattro stati: pronta (con totale), registrazione, elaborazione, risultato. */
    @Test
    fun bubbleStates() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val states = listOf(
            BubbleOverlay.State.IDLE to null,
            BubbleOverlay.State.IDLE to "7",
            BubbleOverlay.State.RECORDING to null,
            BubbleOverlay.State.PROCESSING to "…",
            BubbleOverlay.State.RESULT to "+3",
        )
        states.forEachIndexed { i, (state, text) ->
            val bubble = BubbleOverlay(context, onTap = {}, onLongPress = {})
            bubble.setState(state, text)
            val root = bubble.privateField<FrameLayout>("root")
            canvas.drawView(root, dp(12), dp(220) + i * dp(76), dp(56), dp(56))
        }
        save(bitmap, "overlay-bubble-states")
    }

    /** Menu circolare aperto toccando la bolla (Punta, Tutti, Termina). */
    @Test
    fun radialMenu() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val bubble = BubbleOverlay(context, onTap = {}, onLongPress = {})
        val bubbleLeft = dp(12)
        val bubbleTop = dp(220)
        val menu = RadialMenuOverlay(
            context = context,
            anchorX = bubbleLeft + dp(28),
            anchorY = bubbleTop + dp(28),
            items = listOf(
                RadialMenuOverlay.Item(R.drawable.ic_aim, context.getString(R.string.radial_point), 0xFFFE2C55.toInt()) {},
                RadialMenuOverlay.Item(R.drawable.ic_motion, context.getString(R.string.radial_all), 0xFF00897B.toInt()) {},
                RadialMenuOverlay.Item(R.drawable.ic_close, context.getString(R.string.radial_end), 0xFF616161.toInt()) {},
            ),
            onDismiss = {},
        )
        canvas.drawFullScreen(menu.privateField("root"))
        canvas.drawView(bubble.privateField<FrameLayout>("root"), bubbleLeft, bubbleTop, dp(56), dp(56))
        save(bitmap, "overlay-radial-menu")
    }

    /** Mirino "Punta": striscia in alto con l'istruzione e Annulla, il resto dello schermo è trasparente. */
    @Test
    fun aim() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val aim = AimOverlay(context, onPoint = { _, _ -> }, onCancel = {})
        canvas.drawFullScreen(aim.privateField("root"))
        save(bitmap, "overlay-aim")
    }

    private fun lockOverlay(zone: Rect?, tiles: List<Rect> = emptyList(), focus: Rect? = null): CaptureLockOverlay {
        val lock = CaptureLockOverlay(context, zone, 8000, tiles, focus)
        // la View legge le dimensioni dello schermo dal WindowManager (1078x2399 con i soli qualificatori): le si forza a 1080x2400
        lock.privateField<IntArray>("screenSize").apply { this[0] = Phone.WIDTH_PX; this[1] = Phone.HEIGHT_PX }
        return lock
    }

    /**
     * Disegna lo schermo bloccato con il conto alla rovescia a [elapsedMs] dall'avvio. L'avvio vero parte da show(),
     * che qui non si chiama: lo si simula impostando startedAt. Prima si fa un disegno a vuoto, così quello vero segue
     * subito l'impostazione dell'orario e il conto alla rovescia (che legge l'orologio) risulta identico tra una
     * registrazione e l'altra.
     */
    private fun Canvas.drawLock(lock: CaptureLockOverlay, elapsedMs: Long = 3200) {
        val view = lock.privateField<View>("view")
        Canvas(Bitmap.createBitmap(Phone.WIDTH_PX, Phone.HEIGHT_PX, Bitmap.Config.ARGB_8888)).drawFullScreen(view)
        lock.javaClass.getDeclaredField("startedAt").apply { isAccessible = true }.setLong(lock, System.currentTimeMillis() - elapsedMs)
        drawFullScreen(view)
    }

    /** "Punta": resta in chiaro solo lo sticker toccato, con cornice, conto alla rovescia e barra di avanzamento. */
    @Test
    fun lockPoint() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val tile = FakeArt.screenFrameTiles()[4]
        val zone = Rect(tile).apply { inset(-dp(24), -dp(24)) }
        val lock = lockOverlay(zone = zone, focus = tile)
        canvas.drawLock(lock)
        save(bitmap, "overlay-lock-point")
    }

    /** "Tutti" senza griglia riconosciuta: cornice rossa sottile ai bordi, etichetta in alto e barra in basso. */
    @Test
    fun lockAll() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val lock = lockOverlay(zone = null)
        canvas.drawLock(lock)
        save(bitmap, "overlay-lock-all")
    }

    /** "Tutti" con griglia: elaborazione delle tessere una alla volta ("Sticker 4 di 9"). */
    @Test
    fun lockGridProcessing() {
        val bitmap = screen()
        val canvas = Canvas(bitmap)
        val tiles = FakeArt.screenFrameTiles()
        val union = Rect(tiles[0]).apply { tiles.forEach { union(it) }; inset(-dp(8), -dp(8)) }
        val lock = lockOverlay(zone = union, tiles = tiles)
        lock.showProcessing(3, context.getString(R.string.lock_processing, 4, tiles.size))
        canvas.drawLock(lock)
        save(bitmap, "overlay-lock-grid-processing")
    }
}
