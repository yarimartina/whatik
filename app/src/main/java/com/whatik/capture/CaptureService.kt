package com.whatik.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.StickerLibrary
import com.whatik.image.StickerConverter
import com.whatik.image.StickerDetector
import com.whatik.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sessione di cattura automatica: proiezione dello schermo + bolla flottante sopra TikTok.
 * Un tocco sulla bolla cattura [CAPTURE_MS] di schermo; i fotogrammi vengono analizzati
 * da [StickerDetector] e gli sticker trovati finiscono direttamente in libreria.
 */
class CaptureService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bubble: BubbleOverlay? = null
    private var tornDown = false

    @Volatile private var capturing = false
    private var captureStartedAt = 0L
    private var lastSampleAt = 0L
    private var sessionDir: File? = null
    private val frameFiles = ArrayList<File>()
    private val frameTimes = ArrayList<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("whatik-capture").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (projection != null) teardown()
                tornDown = false
                startAsForeground()
                if (!startProjection(resultCode, data)) {
                    status.value = getString(R.string.capture_failed, "MediaProjection")
                    teardown()
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBubble()
                running.value = true
                status.value = getString(R.string.capture_status_ready)
            }
            ACTION_STOP -> {
                teardown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------ avvio

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(getString(R.string.capture_notification_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.capture_notification_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startProjection(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        } ?: return false
        projection = mp
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        teardown()
                        stopSelf()
                    }
                }
            },
            handler,
        )
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        // mezza risoluzione: basta per sticker a 512 px e dimezza il lavoro
        val width = (metrics.widthPixels / 2 / 2) * 2
        val height = (metrics.heightPixels / 2 / 2) * 2
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        imageReader = reader
        virtualDisplay = try {
            mp.createVirtualDisplay(
                "whatik-capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            )
        } catch (e: Exception) {
            null
        }
        return virtualDisplay != null
    }

    private fun showBubble() {
        bubble = BubbleOverlay(
            context = this,
            onTap = { beginCapture() },
            onLongPress = {
                teardown()
                stopSelf()
            },
        ).also { it.show() }
    }

    // ------------------------------------------------------------ cattura

    private fun beginCapture() {
        if (capturing) return
        val dir = File(cacheDir, "capture/${System.currentTimeMillis()}").apply { mkdirs() }
        sessionDir = dir
        frameFiles.clear()
        frameTimes.clear()
        bubble?.setVisible(false) // la bolla non deve finire nei fotogrammi
        status.value = getString(R.string.capture_status_recording)
        handler.post {
            captureStartedAt = SystemClock.elapsedRealtime()
            lastSampleAt = 0
            capturing = true
        }
        // se lo schermo non cambia non arrivano fotogrammi: chiudiamo comunque la cattura
        handler.postDelayed({ finishCapture() }, CAPTURE_MS + 700)
    }

    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!capturing) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleAt < 1000L / CAPTURE_FPS) return
            lastSampleAt = now
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (rowPadding > 0) Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height) else bitmap
            val file = File(sessionDir, "f${frameFiles.size}.jpg")
            file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (cropped !== bitmap) bitmap.recycle()
            cropped.recycle()
            frameFiles.add(file)
            frameTimes.add(now - captureStartedAt)
            if (now - captureStartedAt >= CAPTURE_MS) finishCapture()
        } catch (e: Exception) {
            // un fotogramma perso non interrompe la cattura
        } finally {
            image.close()
        }
    }

    private fun finishCapture() {
        if (!capturing) return
        capturing = false
        val files = ArrayList(frameFiles)
        val times = ArrayList(frameTimes)
        mainHandler.post {
            bubble?.setVisible(true)
            bubble?.setState(BubbleOverlay.State.PROCESSING, "…")
        }
        status.value = getString(R.string.capture_status_processing)
        scope.launch { process(files, times) }
    }

    private suspend fun process(files: List<File>, times: List<Long>) {
        val library = (application as WhatikApp).library
        val result = runCatching {
            if (files.size < MIN_FRAMES) throw StickerConverter.ConversionException(getString(R.string.capture_too_few_frames))
            val captured = CapturedFrames(files, times)
            val proposals = captured.detect(StickerDetector.Params(ignoreTopFraction = 0.06f, ignoreBottomFraction = 0.05f))
            var created = 0
            val stamp = SimpleDateFormat("HH.mm.ss", Locale.getDefault()).format(Date())
            for ((i, proposal) in proposals.withIndex()) {
                val converted = StickerConverter.convert(captured.producer(proposal.crop, proposal.startMs, proposal.endMs), forceStatic = false)
                val name = if (proposals.size > 1) "TikTok $stamp ${i + 1}" else "TikTok $stamp"
                when (library.importBytes(converted.bytes, name, "capture")) {
                    is StickerLibrary.ImportResult.Added -> created++
                    is StickerLibrary.ImportResult.Duplicate -> created++
                    is StickerLibrary.ImportResult.Failed -> Unit
                }
            }
            created to proposals.size
        }
        files.forEach { it.delete() }
        withContext(Dispatchers.Main) {
            result.onSuccess { (created, found) ->
                val text = if (found > 0) resources.getQuantityString(R.plurals.capture_status_created, created, created)
                else getString(R.string.capture_status_none)
                status.value = text
                updateNotification(text)
                if (found > 0) {
                    bubble?.setState(BubbleOverlay.State.RESULT, "+$created")
                    mainHandler.postDelayed({ bubble?.setState(BubbleOverlay.State.IDLE) }, 2500)
                } else {
                    bubble?.setState(BubbleOverlay.State.IDLE)
                }
            }.onFailure { e ->
                val text = getString(R.string.capture_failed, e.message ?: e.javaClass.simpleName)
                status.value = text
                updateNotification(text)
                bubble?.setState(BubbleOverlay.State.IDLE)
            }
        }
    }

    // ------------------------------------------------------------ chiusura

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        capturing = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { bubble?.hide() }
        bubble = null
        File(cacheDir, "capture").deleteRecursively()
        running.value = false
        status.value = getString(R.string.capture_status_stopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "com.whatik.capture.START"
        const val ACTION_STOP = "com.whatik.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 41
        const val CAPTURE_MS = 5000L
        const val CAPTURE_FPS = 10
        private const val MIN_FRAMES = 6

        /** true mentre la sessione (bolla + proiezione) è attiva. */
        val running = MutableStateFlow(false)

        /** Ultimo messaggio di stato leggibile dall'interfaccia. */
        val status: MutableStateFlow<String?> = MutableStateFlow(null)

        fun start(context: android.content.Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }

        val runningState: StateFlow<Boolean> get() = running
    }
}
