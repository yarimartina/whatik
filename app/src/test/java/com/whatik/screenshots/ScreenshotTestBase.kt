package com.whatik.screenshots

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.whatik.ui.theme.WhatikTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Dove finiscono i PNG: la cartella di Roborazzi (build/outputs/roborazzi) oppure quella indicata dal plugin. */
object ScreenshotOutput {
    fun file(name: String): File {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi")
        dir.mkdirs()
        return File(dir, "$name.png")
    }
}

/** Schermo del telefono simulato: Pixel 7, 1080x2400 px a 420 dpi (2,625x). */
object Phone {
    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 2400
    const val DENSITY = 2.625f

    /** Come un Pixel 7 con navigazione a gesti: barra di stato e barra di navigazione in dp. */
    const val STATUS_BAR_DP = 24
    const val NAV_BAR_DP = 24

    /**
     * Qualificatori Robolectric: italiano (la lingua predefinita dell'app), 411x914 dp a 420 dpi.
     * Le dimensioni esatte in pixel (1080x2400) le imposta [PhoneSetupRule]: con i soli qualificatori
     * Robolectric arrotonderebbe a 1078x2399.
     */
    const val QUALIFIERS_BASE = "it-w411dp-h914dp"
    const val LIGHT = "$QUALIFIERS_BASE-notnight-420dpi"
    const val DARK = "$QUALIFIERS_BASE-night-420dpi"
}

/** Prepara l'ambiente Robolectric prima che parta l'activity: dimensione dello schermo, activity, WhatsApp. */
class PhoneSetupRule : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val display = ShadowDisplay.getDefaultDisplay()
        shadowOf(display).setWidth(Phone.WIDTH_PX)
        shadowOf(display).setHeight(Phone.HEIGHT_PX)
        val packageManager = shadowOf(app.packageManager)
        // la finestra "Informazioni" mostra versione e codice: il codice di build cambia a ogni minuto, qui è fisso
        packageManager.getInternalMutablePackageInfo(app.packageName)?.apply {
            versionName = "1.0.57"
            @Suppress("DEPRECATION")
            versionCode = 412_345
            longVersionCode = 412_345L
        }
        // ComponentActivity è l'activity vuota in cui il test inserisce le schermate (di solito la dichiara ui-test-manifest,
        // che finirebbe però nell'APK di debug: qui la si registra solo nel test)
        packageManager.addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        // WhatsApp "installato": altrimenti la finestra di esportazione mostra l'avviso di WhatsApp mancante
        packageManager.installPackage(
            PackageInfo().apply {
                packageName = "com.whatsapp"
                applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp"; enabled = true }
            },
        )
    }
}

/** Sostituisce i modelli Coil che non sono file locali (content:// e https://) con i PNG finti. */
private class FakeRemoteImages(private val files: Map<String, File>) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val file = files[chain.request.data.toString()] ?: return chain.proceed(chain.request)
        return chain.proceed(chain.request.newBuilder().data(file).build())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTestBase(private val suffix: String) {

    private val tmp = TemporaryFolder()
    protected val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(PhoneSetupRule()).around(compose)

    protected lateinit var fake: FakeData

    @Before
    fun prepareFakeData() {
        fake = FakeData(tmp.newFolder("fake"))
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Coil carica le immagini in modo sincrono (Unconfined) e senza bitmap hardware, che il rendering
        // su JVM non sa disegnare; i link http e gli URI content:// puntano ai PNG generati.
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(FakeRemoteImages(fake.remoteImages)) }
                .dispatcher(Dispatchers.Unconfined)
                .allowHardware(false)
                .crossfade(false)
                .build(),
        )
    }

    @After
    fun resetImageLoader() {
        Coil.reset()
    }

    /** Mostra una schermata nel tema dell'app, con barra di stato e di navigazione come su un telefono vero. */
    protected fun show(dimmed: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { WhatikTheme { content(); if (dimmed) Scrim() } }
        applySystemBars()
        settle()
    }

    /**
     * Come [show], ma senza aspettare che Compose "sia a riposo": serve ai dialoghi con campi di testo
     * (OutlinedTextField dentro AlertDialog), che con Robolectric tengono sempre occupato il controllo di
     * inattività del test di Compose (AppNotIdleException dopo 60 s) pur restando disegnabili. Si fa girare
     * il looper a mano per qualche fotogramma e poi si scatta con [snapScreen].
     */
    protected fun showDirect(dimmed: Boolean = false, content: @Composable () -> Unit) {
        compose.activity.setContent { WhatikTheme { content(); if (dimmed) Scrim() } }
        idleFrames(3)
        applySystemBars()
        idleFrames(12)
    }

    /**
     * Velo scuro dietro i dialoghi: sul telefono lo fa la finestra del dialogo (dim del Window), che nel
     * rendering su JVM non viene disegnato; qui lo si ricrea con lo scrim di Material 3 (nero al 32%).
     */
    @Composable
    private fun Scrim() {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
    }

    private fun idleFrames(count: Int) {
        repeat(count) { ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS) }
    }

    /** Lascia girare le ricomposizioni e il caricamento delle immagini (Coil) prima dello scatto. */
    protected fun settle() {
        repeat(8) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(60)
        }
        compose.waitForIdle()
    }

    /** Robolectric non ha barre di sistema: si inviano a Compose gli stessi inset di un Pixel 7 a gesti. */
    private fun applySystemBars() {
        val density = Phone.DENSITY
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (Phone.STATUS_BAR_DP * density).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (Phone.NAV_BAR_DP * density).toInt()))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        // il thread del test è il thread principale di Robolectric
        val composeView = findComposeView(compose.activity.window.decorView)
        if (composeView != null) ViewCompat.dispatchApplyWindowInsets(composeView, insets)
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findComposeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Scatta la finestra principale. */
    protected fun snap(name: String) {
        compose.onRoot().captureRoboImage(ScreenshotOutput.file("$name-$suffix"))
    }

    /** Scatta l'intero schermo, anche finestre sovrapposte (dialoghi, menu a comparsa). */
    protected fun snapScreen(name: String) {
        captureScreenRoboImage(ScreenshotOutput.file("$name-$suffix"))
    }
}
