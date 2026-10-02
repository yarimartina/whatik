package com.whatik.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.StickerItem
import com.whatik.data.StickerLibrary
import com.whatik.image.StickerConverter
import com.whatik.image.CropSpec
import com.whatik.image.StickerDetector
import com.whatik.image.StickerRefiner
import com.whatik.image.TileGridFinder
import com.whatik.image.WebPContainer
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
    private var aim: AimOverlay? = null
    private var radial: RadialMenuOverlay? = null
    private var lock: CaptureLockOverlay? = null
    private var tornDown = false
    private var screenWidth = 1
    private var screenHeight = 1
    private var captureWidth = 1
    private var captureHeight = 1

    /** Punto toccato (in pixel del fotogramma catturato) per la modalità "punta e cattura"; null = tutto ciò che si muove. */
    private var pointOfInterest: IntArray? = null
    private var captureDurationMs = CAPTURE_MS
    /** Zona inquadrata (in pixel del fotogramma catturato) a cui ritagliare i fotogrammi; null = tutto lo schermo. */
    private var zone: android.graphics.Rect? = null
    private var sampleIntervalMs = 1000L / CAPTURE_FPS_ALL
    /** Tessere della griglia (in pixel del fotogramma catturato) per la modalita' "tutti"; vuoto = cattura libera. */
    private var gridTiles: List<android.graphics.Rect> = emptyList()
    /** Richiesta di un singolo fotogramma (per riconoscere la griglia prima di registrare). */
    @Volatile private var snapshotRequest: ((Bitmap) -> Unit)? = null

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
                sessionCount.value = 0
                createdIds.value = emptyList()
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
        // risoluzione piena (tetto 1440 px di larghezza): a metà risoluzione gli sticker
        // uscivano sgranati una volta portati a 512 px
        val scale = if (metrics.widthPixels > MAX_CAPTURE_WIDTH) MAX_CAPTURE_WIDTH.toFloat() / metrics.widthPixels else 1f
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
        captureWidth = width
        captureHeight = height
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
            onTap = { showRadialMenu() },
            onLongPress = {
                teardown()
                stopSelf()
            },
        ).also { it.show() }
    }

    /** Menu circolare attorno alla bolla: punta e cattura, tutti quelli in movimento, termina. */
    private fun showRadialMenu() {
        if (capturing || radial != null || aim != null) return
        val center = bubble?.center() ?: return
        radial = RadialMenuOverlay(
            context = this,
            anchorX = center[0],
            anchorY = center[1],
            items = listOf(
                RadialMenuOverlay.Item(R.drawable.ic_aim, getString(R.string.radial_point), 0xFFFE2C55.toInt()) {
                    dismissRadial()
                    showAim()
                },
                RadialMenuOverlay.Item(R.drawable.ic_motion, getString(R.string.radial_all), 0xFF00897B.toInt()) {
                    dismissRadial()
                    beginGridCapture()
                },
                RadialMenuOverlay.Item(R.drawable.ic_close, getString(R.string.radial_end), 0xFF616161.toInt()) {
                    dismissRadial()
                    teardown()
                    stopSelf()
                },
            ),
            onDismiss = { dismissRadial() },
        ).also { it.show() }
    }

    private fun dismissRadial() {
        runCatching { radial?.hide() }
        radial = null
    }

    /** Mirino: un tocco sullo sticker lo cattura (animato o fermo). */
    private fun showAim() {
        if (capturing || aim != null) return
        aim = AimOverlay(
            context = this,
            onPoint = { x, y ->
                dismissAim()
                val px = (x * captureWidth / screenWidth).toInt().coerceIn(0, captureWidth - 1)
                val py = (y * captureHeight / screenHeight).toInt().coerceIn(0, captureHeight - 1)
                beginCapture(intArrayOf(px, py))
            },
            onCancel = { dismissAim() },
        ).also { it.show() }
        status.value = getString(R.string.aim_hint)
    }

    private fun dismissAim() {
        runCatching { aim?.hide() }
        aim = null
    }

    // ------------------------------------------------------------ cattura

    /**
     * "Tutti": fotografa lo schermo, riconosce le tessere del pannello sticker e registra solo
     * quell'area; poi ogni tessera viene elaborata (animata o ferma) una alla volta.
     * Senza griglia riconoscibile si ripiega sulla cattura libera di cio' che si muove.
     */
    private fun beginGridCapture() {
        if (capturing) return
        status.value = getString(R.string.capture_status_grid_search)
        // il menu deve sparire dallo schermo prima della fotografia; nascondere la bolla produce
        // comunque un nuovo fotogramma anche se sullo schermo non si muove nulla
        handler.postDelayed({
            mainHandler.post { bubble?.setVisible(false) }
            snapshotRequest = { frame ->
                scope.launch {
                    val tiles = runCatching { findTiles(frame) }.getOrDefault(emptyList())
                    frame.recycle()
                    withContext(Dispatchers.Main) {
                        if (tiles.isEmpty()) {
                            Toast.makeText(this@CaptureService, R.string.capture_no_grid, Toast.LENGTH_SHORT).show()
                            beginCapture(null)
                        } else {
                            status.value = resources.getQuantityString(R.plurals.capture_status_grid_found, tiles.size, tiles.size)
                            beginCapture(null, tiles)
                        }
                    }
                }
            }
            // schermo immobile e nessun fotogramma: si ripiega sulla cattura libera
            handler.postDelayed({
                if (snapshotRequest != null) {
                    snapshotRequest = null
                    mainHandler.post {
                        Toast.makeText(this@CaptureService, R.string.capture_no_grid, Toast.LENGTH_SHORT).show()
                        beginCapture(null)
                    }
                }
            }, 2000)
        }, 300)
    }

    /** Tessere del pannello in pixel del fotogramma catturato, a partire da una copia ridotta. */
    private fun findTiles(frame: Bitmap): List<android.graphics.Rect> {
        val aw = 480
        val ah = (frame.height.toFloat() * aw / frame.width).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(frame, aw, ah, true)
        val rgb = IntArray(aw * ah)
        small.getPixels(rgb, 0, aw, 0, 0, aw, ah)
        small.recycle()
        val sx = frame.width.toFloat() / aw
        val sy = frame.height.toFloat() / ah
        return TileGridFinder.find(rgb, aw, ah).map { (l, t, w, h) ->
            android.graphics.Rect((l * sx).toInt(), (t * sy).toInt(), ((l + w) * sx).toInt(), ((t + h) * sy).toInt())
        }
    }

    private fun beginCapture(point: IntArray?, tiles: List<android.graphics.Rect> = emptyList()) {
        if (capturing) return
        val dir = File(cacheDir, "capture/${System.currentTimeMillis()}").apply { mkdirs() }
        sessionDir = dir
        frameFiles.clear()
        frameTimes.clear()
        captureDurationMs = CAPTURE_MS
        if (point != null) {
            // zona inquadrata attorno al punto: i fotogrammi vengono ritagliati qui, cosi' si puo'
            // campionare piu' spesso (sticker fluidi) e tutto cio' che si disegna fuori non entra
            val side = (captureWidth * ZONE_FRACTION).toInt().coerceAtMost(minOf(captureWidth, captureHeight))
            val zl = (point[0] - side / 2).coerceIn(0, captureWidth - side)
            val zt = (point[1] - side / 2).coerceIn(0, captureHeight - side)
            zone = android.graphics.Rect(zl, zt, zl + side, zt + side)
            pointOfInterest = intArrayOf(point[0] - zl, point[1] - zt)
            sampleIntervalMs = 1000L / CAPTURE_FPS_POINT
        } else if (tiles.isNotEmpty()) {
            // griglia: si registra solo l'area delle tessere (con un margine), a frequenza piu' alta
            val union = android.graphics.Rect(tiles[0])
            tiles.forEach { union.union(it) }
            val margin = (captureWidth * 0.02f).toInt()
            union.inset(-margin, -margin)
            union.intersect(0, 0, captureWidth, captureHeight)
            zone = union
            gridTiles = tiles
            pointOfInterest = null
            sampleIntervalMs = 1000L / CAPTURE_FPS_GRID
        } else {
            zone = null
            gridTiles = emptyList()
            pointOfInterest = null
            sampleIntervalMs = 1000L / CAPTURE_FPS_ALL
        }
        if (point != null) gridTiles = emptyList()
        bubble?.setVisible(false) // la bolla non deve finire nei fotogrammi
        val screenZone = zone?.let { z ->
            android.graphics.Rect(
                z.left * screenWidth / captureWidth, z.top * screenHeight / captureHeight,
                z.right * screenWidth / captureWidth, z.bottom * screenHeight / captureHeight,
            )
        }
        val screenTiles = gridTiles.map { r ->
            android.graphics.Rect(
                r.left * screenWidth / captureWidth, r.top * screenHeight / captureHeight,
                r.right * screenWidth / captureWidth, r.bottom * screenHeight / captureHeight,
            )
        }
        runCatching { lock?.hide() }
        lock = CaptureLockOverlay(this, screenZone, captureDurationMs, screenTiles).also { it.show() }
        status.value = getString(R.string.capture_status_recording)
        // breve attesa perché mirino e bolla spariscano dallo schermo prima del primo fotogramma
        handler.postDelayed({
            captureStartedAt = SystemClock.elapsedRealtime()
            lastSampleAt = 0
            capturing = true
        }, 350)
        // se lo schermo non cambia non arrivano fotogrammi: chiudiamo comunque la cattura
        handler.postDelayed({ finishCapture() }, 350 + captureDurationMs + 700)
    }

    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            snapshotRequest?.let { request ->
                snapshotRequest = null
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val bitmap = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val snapshot = if (rowPadding > 0) Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() } else bitmap
                request(snapshot)
                return
            }
            if (!capturing) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleAt < sampleIntervalMs) return
            lastSampleAt = now
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val z = zone
            val cropped = when {
                z != null -> Bitmap.createBitmap(bitmap, z.left, z.top, z.width(), z.height())
                rowPadding > 0 -> Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                else -> bitmap
            }
            val file = File(sessionDir, "f${frameFiles.size}.jpg")
            file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (cropped !== bitmap) bitmap.recycle()
            cropped.recycle()
            frameFiles.add(file)
            frameTimes.add(now - captureStartedAt)
            if (now - captureStartedAt >= captureDurationMs) finishCapture()
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
        val point = pointOfInterest
        val tiles = gridTiles
        val zoneRect = zone
        mainHandler.post {
            if (tiles.isEmpty()) {
                runCatching { lock?.hide() }
                lock = null
            } else {
                lock?.showProcessing(0, getString(R.string.lock_processing, 1, tiles.size))
            }
            bubble?.setVisible(true)
            bubble?.setState(BubbleOverlay.State.PROCESSING, "…")
        }
        status.value = getString(R.string.capture_status_processing)
        if (tiles.isNotEmpty() && zoneRect != null) {
            scope.launch { processGrid(files, times, tiles, zoneRect) }
        } else {
            scope.launch { process(files, times, point) }
        }
    }

    private suspend fun process(files: List<File>, times: List<Long>, point: IntArray?) {
        val library = (application as WhatikApp).library
        val result = runCatching {
            if (files.isEmpty()) throw StickerConverter.ConversionException(getString(R.string.capture_too_few_frames))
            val captured = CapturedFrames(files, times)
            val stamp = SimpleDateFormat("HH.mm.ss", Locale.getDefault()).format(Date())
            val items = ArrayList<StickerItem>()
            var preview: Bitmap? = null
            var found = 0
            var animated = false

            suspend fun store(bytes: ByteArray, name: String): StickerItem? {
                val item = when (val r = library.importBytes(bytes, name, "capture")) {
                    is StickerLibrary.ImportResult.Added -> r.item
                    is StickerLibrary.ImportResult.Duplicate -> r.item
                    is StickerLibrary.ImportResult.Failed -> null
                }
                if (item != null) {
                    items.add(item)
                    if (preview == null) preview = firstFrame(bytes)
                }
                return item
            }

            // a schermo intero si ignorano le fasce di sistema; la zona ritagliata non ne ha
            val detectorParams = if (point == null) StickerDetector.Params(ignoreTopFraction = 0.06f, ignoreBottomFraction = 0.05f) else StickerDetector.Params()
            val (grays, dims) = captured.grayFrames()
            val proposals = if (files.size >= MIN_FRAMES) StickerDetector.detect(grays, dims.first, dims.second, times, detectorParams) else emptyList()
            val (rgb, fw, fh) = captured.colorFrame()

            // Il movimento da solo taglia gli sticker (si muove solo una parte): si allarga ai bordi
            // dello sticker fermo e si scartano le regioni evidentemente sbagliate.
            fun refined(p: StickerDetector.Proposal, seed: IntArray?): CropSpec? =
                StickerRefiner.refine(p.box, dims.first, dims.second, rgb, fw, fh, seed)

            if (point == null) {
                // tutto ciò che si muove
                val crops = proposals.mapNotNull { p -> refined(p, null)?.let { crop -> Triple(crop, p.startMs, p.endMs) } }
                for ((i, entry) in crops.withIndex()) {
                    val (crop, startMs, endMs) = entry
                    val converted = StickerConverter.convert(captured.producer(crop, startMs, endMs), forceStatic = false)
                    store(converted.bytes, if (crops.size > 1) "TikTok $stamp ${i + 1}" else "TikTok $stamp")
                }
                found = crops.size
                animated = crops.isNotEmpty()
            } else {
                // punta e cattura: una regione in movimento che contiene il punto, altrimenti uno sticker fermo
                val hit = proposals.firstOrNull { p ->
                    val (l, t, cw, ch) = p.crop.toPixels(captured.width, captured.height)
                    val slack = maxOf(cw, ch) / 4
                    point[0] in (l - slack)..(l + cw + slack) && point[1] in (t - slack)..(t + ch + slack)
                }
                val seed = intArrayOf(point[0] * fw / captured.width, point[1] * fh / captured.height)
                val hitCrop = hit?.let { refined(it, seed) ?: it.crop }
                if (hit != null && hitCrop != null) {
                    val converted = StickerConverter.convert(captured.producer(hitCrop, hit.startMs, hit.endMs), forceStatic = false)
                    store(converted.bytes, "TikTok $stamp")
                    animated = true
                } else {
                    val crop = captured.staticCropAround(point[0], point[1])
                    val converted = StickerConverter.convert(captured.producer(crop, times.first(), times.first()), forceStatic = true)
                    store(converted.bytes, "TikTok $stamp")
                }
                found = 1
            }
            CaptureOutcome(items, found, preview, animated)
        }
        files.forEach { it.delete() }
        withContext(Dispatchers.Main) {
            result.onSuccess { outcome ->
                val created = outcome.items.size
                sessionCount.value += created
                createdIds.value = createdIds.value + outcome.items.map { it.id }
                val total = sessionCount.value
                val kind = getString(if (outcome.animated) R.string.capture_kind_animated else R.string.capture_kind_static)
                val text = if (outcome.found > 0) resources.getQuantityString(R.plurals.capture_status_created, created, created) + " ($kind)"
                else getString(R.string.capture_status_none)
                status.value = text
                updateNotification(getString(R.string.capture_notification_progress, text, total))
                if (outcome.found > 0) {
                    Toast.makeText(this@CaptureService, text, Toast.LENGTH_SHORT).show()
                    notifyResult(outcome)
                    bubble?.setState(BubbleOverlay.State.RESULT, "+$created")
                    mainHandler.postDelayed({ bubble?.setState(BubbleOverlay.State.IDLE, total.toString()) }, 2500)
                } else {
                    bubble?.setState(BubbleOverlay.State.IDLE, if (total > 0) total.toString() else null)
                }
            }.onFailure { e ->
                val text = getString(R.string.capture_failed, e.message ?: e.javaClass.simpleName)
                status.value = text
                updateNotification(text)
                bubble?.setState(BubbleOverlay.State.IDLE, sessionCount.value.takeIf { it > 0 }?.toString())
            }
        }
    }

    /** Elabora le tessere una alla volta: animata se nella tessera c'e' un loop, altrimenti ferma. */
    private suspend fun processGrid(files: List<File>, times: List<Long>, tiles: List<android.graphics.Rect>, zoneRect: android.graphics.Rect) {
        val library = (application as WhatikApp).library
        val stamp = SimpleDateFormat("HH.mm.ss", Locale.getDefault()).format(Date())
        val items = ArrayList<StickerItem>()
        var preview: Bitmap? = null
        var anyAnimated = false
        val failures = ArrayList<String>()
        val captured = runCatching { CapturedFrames(files, times) }.getOrNull()
        val proposals = if (captured != null && files.size >= MIN_FRAMES) runCatching {
            val (grays, dims) = captured.grayFrames()
            StickerDetector.detect(grays, dims.first, dims.second, times, StickerDetector.Params())
        }.getOrDefault(emptyList()) else emptyList()

        for ((i, tile) in tiles.withIndex()) {
            withContext(Dispatchers.Main) { lock?.showProcessing(i, getString(R.string.lock_processing, i + 1, tiles.size)) }
            if (captured == null) break
            try {
                // tessera in coordinate della zona registrata
                val local = android.graphics.Rect(tile.left - zoneRect.left, tile.top - zoneRect.top, tile.right - zoneRect.left, tile.bottom - zoneRect.top)
                local.intersect(0, 0, captured.width, captured.height)
                val crop = CropSpec.fromPixels(local.left, local.top, local.width(), local.height(), captured.width, captured.height)
                // un loop il cui centro cade nella tessera -> animata, con i tempi del loop
                val hit = proposals.firstOrNull { p ->
                    val (l, t, w, h) = p.crop.toPixels(captured.width, captured.height)
                    local.contains(l + w / 2, t + h / 2)
                }
                val converted = if (hit != null) {
                    StickerConverter.convert(captured.producer(crop, hit.startMs, hit.endMs), forceStatic = false)
                } else {
                    StickerConverter.convert(captured.producer(crop, times.first(), times.first()), forceStatic = true)
                }
                if (converted.animated) anyAnimated = true
                val name = if (tiles.size > 1) "TikTok $stamp ${i + 1}" else "TikTok $stamp"
                val item = when (val r = library.importBytes(converted.bytes, name, "capture")) {
                    is StickerLibrary.ImportResult.Added -> r.item
                    is StickerLibrary.ImportResult.Duplicate -> r.item
                    is StickerLibrary.ImportResult.Failed -> null
                }
                if (item != null) {
                    items.add(item)
                    if (preview == null) preview = firstFrame(converted.bytes)
                    withContext(Dispatchers.Main) { bubble?.setState(BubbleOverlay.State.RESULT, "+${items.size}") }
                }
            } catch (e: Exception) {
                failures.add("${i + 1}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        files.forEach { it.delete() }
        withContext(Dispatchers.Main) {
            runCatching { lock?.hide() }
            lock = null
            val created = items.size
            sessionCount.value += created
            createdIds.value = createdIds.value + items.map { it.id }
            val total = sessionCount.value
            val text = if (created > 0) resources.getQuantityString(R.plurals.capture_status_created, created, created)
            else getString(R.string.capture_status_none)
            status.value = text
            updateNotification(getString(R.string.capture_notification_progress, text, total))
            if (created > 0) {
                Toast.makeText(this@CaptureService, text, Toast.LENGTH_SHORT).show()
                notifyResult(CaptureOutcome(items, tiles.size, preview, anyAnimated))
                mainHandler.postDelayed({ bubble?.setState(BubbleOverlay.State.IDLE, total.toString()) }, 2500)
            } else {
                bubble?.setState(BubbleOverlay.State.IDLE, if (total > 0) total.toString() else null)
            }
        }
    }

    private class CaptureOutcome(val items: List<StickerItem>, val found: Int, val preview: Bitmap?, val animated: Boolean)

    /** Primo fotogramma dello sticker convertito, per l'anteprima nella notifica. */
    private fun firstFrame(webp: ByteArray): Bitmap? = runCatching {
        val frame = WebPContainer.parse(webp).frames.first().standalone
        BitmapFactory.decodeByteArray(frame, 0, frame.size)
    }.getOrNull()

    /** Notifica separata con anteprima e nomi: toccandola si apre Whatik con gli sticker selezionati. */
    private fun notifyResult(outcome: CaptureOutcome) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, getString(R.string.capture_result_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val ids = ArrayList(createdIds.value)
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putStringArrayListExtra(MainActivity.EXTRA_SELECT_IDS, ids),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val names = outcome.items.joinToString(", ") { it.displayName }
        val builder = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(resources.getQuantityString(R.plurals.capture_result_title, outcome.items.size, outcome.items.size))
            .setContentText(names)
            .setSubText(resources.getQuantityString(R.plurals.capture_session_captured, sessionCount.value, sessionCount.value))
            .setContentIntent(open)
            .setAutoCancel(true)
        outcome.preview?.let { builder.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?).setSummaryText(names)) }
        manager.notify(RESULT_NOTIFICATION_ID, builder.build())
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
        dismissAim()
        dismissRadial()
        runCatching { lock?.hide() }
        lock = null
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
        private const val RESULT_CHANNEL_ID = "capture_results"
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_CAPTURE_WIDTH = 1440
        /** Durata della cattura: abbastanza da contenere due giri di un loop fino a ~4 s. */
        const val CAPTURE_MS = 8000L
        const val CAPTURE_FPS_ALL = 10
        const val CAPTURE_FPS_POINT = 20
        const val CAPTURE_FPS_GRID = 15
        /** Lato della zona inquadrata in "punta e cattura", come frazione della larghezza. */
        const val ZONE_FRACTION = 0.7f
        private const val MIN_FRAMES = 6

        /** true mentre la sessione (bolla + proiezione) è attiva. */
        val running = MutableStateFlow(false)

        /** Ultimo messaggio di stato leggibile dall'interfaccia. */
        val status: MutableStateFlow<String?> = MutableStateFlow(null)

        /** Sticker catturati nella sessione corrente (contatore e id, per mostrarli e selezionarli). */
        val sessionCount = MutableStateFlow(0)
        val createdIds = MutableStateFlow<List<String>>(emptyList())

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
