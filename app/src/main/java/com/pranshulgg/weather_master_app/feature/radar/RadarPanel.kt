package com.pranshulgg.weather_master_app.feature.radar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pranshulgg.weather_master_app.R
import com.pranshulgg.weather_master_app.core.model.domain.AppException
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.model.domain.toMessageRes
import com.pranshulgg.weather_master_app.core.prefs.LocalAppPrefs
import com.pranshulgg.weather_master_app.core.ui.components.ErrorContainer
import com.pranshulgg.weather_master_app.core.ui.components.LoadingPlaceholder
import com.pranshulgg.weather_master_app.core.ui.components.NavigateUpBtn
import com.pranshulgg.weather_master_app.core.ui.components.Symbol
import com.pranshulgg.weather_master_app.core.ui.components.Tooltip
import com.pranshulgg.weather_master_app.core.utils.formatters.to12HourTimeString
import com.pranshulgg.weather_master_app.core.utils.formatters.to24HourTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MIN_WIDTH_KM = 25.0
private const val DEFAULT_WIDTH_KM = 100.0
private val BORDER_WIDTH = 16.dp // left and right edges of the page
private const val OLD_SCAN_MILLI = 30 * 60 * 1000L
private const val LOOP_FRAME_SCALE = 0.5f // loop frames are drawn at half resolution
private const val LOOP_FRAME_MILLI = 500L
private const val LOOP_HOLD_LAST_MILLI = 1500L // the newest frame stays up longer

// Loop frames are drawn two at a time, shared by every run: a run cancelled by another zoom
// finishes its current frames alongside the next run's, so only a few bitmaps are held
private val LoopRenderDispatcher = Dispatchers.Default.limitedParallelism(2)

/**
 * Full-width radar page that slides in from the right over the main screen
 * Right swipe, back or the top bar arrow close it; pinch and double-tap zoom around the location
 */
@Composable
fun RadarPanel(state: RadarPanelState, location: Location) {
    val isVisible by remember { derivedStateOf { state.isVisible } }
    if (!isVisible) return

    val viewModel: RadarViewModel = hiltViewModel()
    // Nothing from a previous location
    val uiState = viewModel.uiState.takeIf { it.locationKey == location.radarKey } ?: RadarUiState()
    val scan = uiState.scan

    // Refresh only while the app is started, not in the background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(location.radarKey, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepUpdated(location) }
    }

    var widthKm by rememberSaveable(location.id) { mutableStateOf(DEFAULT_WIDTH_KM) }
    var gestureScale by remember { mutableFloatStateOf(1f) }
    // A pinch cut short by closing the page never reaches onZoomEnd
    LaunchedEffect(state.isOpen) { if (!state.isOpen) gestureScale = 1f }
    val maxWidthKm = scan?.let {
        2 * (it.rangeKm + distanceKm(location.latitude, location.longitude, it.latitude, it.longitude))
    }?.coerceAtLeast(MIN_WIDTH_KM) ?: 1000.0
    val currentMaxWidthKm by rememberUpdatedState(maxWidthKm)
    val canZoom by rememberUpdatedState(scan != null)
    LaunchedEffect(maxWidthKm) { widthKm = widthKm.coerceIn(MIN_WIDTH_KM, maxWidthKm) }

    // Gestures are read on this fixed layer: reading them on the sliding page would measure the
    // finger relative to a page that moves with it. Only while open, so taps reach the main
    // screen during the close animation
    val pageGestures = if (!state.isOpen) Modifier else Modifier
        .pointerInput(location.id) {
            detectTapGestures(onDoubleTap = {
                if (!canZoom) return@detectTapGestures
                val presets = listOf(MIN_WIDTH_KM, DEFAULT_WIDTH_KM, currentMaxWidthKm)
                val current = presets.indices.minBy { abs(presets[it] - widthKm) }
                widthKm = presets[(current + 1) % presets.size]
            })
        }
        .radarPageGestures(
            key = location.id,
            onCloseDrag = state::dragBy,
            onCloseDragEnd = { velocity -> state.settle(velocity, opening = false) },
            onZoom = { zoom ->
                if (canZoom) gestureScale = (gestureScale * zoom)
                    .coerceIn((widthKm / currentMaxWidthKm).toFloat(), (widthKm / MIN_WIDTH_KM).toFloat())
            },
            onZoomEnd = {
                widthKm = (widthKm / gestureScale).coerceIn(MIN_WIDTH_KM, currentMaxWidthKm)
                gestureScale = 1f
            }
        )

    Box(
        Modifier
            .fillMaxSize()
            .then(pageGestures)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = state.closedFraction * size.width }
                // Black borders on the left and right; the page fits between them
                .background(Color.Black)
                .padding(horizontal = BORDER_WIDTH)
        ) {
            RadarPage(
                location = location,
                scan = scan,
                loop = uiState.loop?.takeIf { uiState.isLoopOn },
                isLoopOn = uiState.isLoopOn,
                error = uiState.error,
                widthKm = widthKm,
                gestureScale = { gestureScale },
                onToggleLoop = { viewModel.toggleLoop(location) },
                onRetry = { viewModel.refreshNow(location) },
                onClose = state::close
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadarPage(
    location: Location,
    scan: RadarScan?,
    loop: List<RadarScan>?,
    isLoopOn: Boolean,
    error: AppException?,
    widthKm: Double,
    gestureScale: () -> Float,
    onToggleLoop: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val is24Hr = LocalAppPrefs.current.is24HrTimeFormat

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = {
                    Column {
                        Text(
                            stringResource(R.string.radar),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (scan != null) {
                            Text(
                                "${scan.station} · ${formatScanTime(scan.scanTimeMilli, location, is24Hr, isOld(scan.scanTimeMilli))}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                navigationIcon = { NavigateUpBtn(onClick = onClose) }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Everything drawn for one location starts afresh for the next
                scan != null -> key(location.radarKey) {
                    RadarImage(
                        scan = scan,
                        loop = loop,
                        isLoopOn = isLoopOn,
                        location = location,
                        is24Hr = is24Hr,
                        widthKm = widthKm,
                        gestureScale = gestureScale,
                        onToggleLoop = onToggleLoop
                    )
                }

                error != null -> ErrorContainer(
                    onRetry = onRetry,
                    errorDescription = stringResource(error.toMessageRes())
                )

                else -> LoadingPlaceholder()
            }
        }
    }
}

/** A bitmap and the map width it was drawn for, to scale it until the current width is drawn */
private class RenderedLayer(val bitmap: ImageBitmap, val widthKm: Double)

private class LoopFrame(val bitmap: ImageBitmap, val scanTimeMilli: Long)

/** Time-lapse frames, drawn at lower resolution for a map width */
private class LoopFrames(val frames: List<LoopFrame>, val widthKm: Double)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadarImage(
    scan: RadarScan,
    loop: List<RadarScan>?,
    isLoopOn: Boolean,
    location: Location,
    is24Hr: Boolean,
    widthKm: Double,
    gestureScale: () -> Float,
    onToggleLoop: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val colorScheme = MaterialTheme.colorScheme
    val colors = remember(colorScheme) {
        RadarColors(
            states = colorScheme.onSurfaceVariant.copy(alpha = 0.8f).toArgb(),
            counties = colorScheme.outlineVariant.copy(alpha = 0.6f).toArgb(),
            roads = colorScheme.tertiary.copy(alpha = 0.45f).toArgb(),
            water = colorScheme.primary.copy(alpha = 0.55f).toArgb(),
            location = colorScheme.primary.toArgb(),
            locationRing = colorScheme.surface.toArgb(),
            site = colorScheme.onSurface.toArgb(),
            siteLabel = colorScheme.onSurfaceVariant.toArgb()
        )
    }

    val outlines by produceState<RadarOutlines?>(null) { value = RadarOutlines.load(context) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var map by remember { mutableStateOf<RenderedLayer?>(null) }
    var echoes by remember { mutableStateOf<RenderedLayer?>(null) }
    var frames by remember { mutableStateOf<LoopFrames?>(null) }
    // Read only by the frame and its time, so the rest of the page isn't recomposed every frame
    val frameIndex = remember { mutableIntStateOf(0) }

    // The map depends only on the radar site, not on each new scan
    LaunchedEffect(scan.station, scan.latitude, scan.longitude, outlines, widthKm, size, colors) {
        val loadedOutlines = outlines ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) {
            RadarRenderer.renderMap(
                site = scan,
                outlines = loadedOutlines,
                centerLatitude = location.latitude,
                centerLongitude = location.longitude,
                widthKm = widthKm,
                widthPx = size.width,
                heightPx = size.height,
                density = density,
                colors = colors
            )
        }
        map = RenderedLayer(bitmap.asImageBitmap(), widthKm)
    }

    LaunchedEffect(scan, widthKm, size) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) {
            RadarRenderer.renderEchoes(
                scan, location.latitude, location.longitude, widthKm, size.width, size.height
            )
        }
        echoes = RenderedLayer(bitmap.asImageBitmap(), widthKm)
    }

    LaunchedEffect(loop, widthKm, size) {
        if (loop == null || size.width == 0 || size.height == 0) {
            frames = null
            return@LaunchedEffect
        }
        // Drop the old frames first so two sets aren't held at once (the latest scan shows meanwhile)
        frames = null
        val rendered = withContext(LoopRenderDispatcher) {
            loop.map { frame ->
                async {
                    ensureActive() // skip frames not started yet when the zoom changes again
                    LoopFrame(
                        RadarRenderer.renderEchoes(
                            frame, location.latitude, location.longitude, widthKm, size.width, size.height,
                            scale = LOOP_FRAME_SCALE
                        ).asImageBitmap(),
                        frame.scanTimeMilli
                    )
                }
            }.awaitAll()
        }
        frames = LoopFrames(rendered, widthKm)
    }

    val frameCount = frames?.frames?.size ?: 0
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A new loop starts from the oldest frame; a zoom keeps the position
    LaunchedEffect(loop) { frameIndex.intValue = 0 }
    LaunchedEffect(frameCount, lifecycle) {
        if (frameCount == 0) return@LaunchedEffect
        // Not while the app is in the background
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var index = frameIndex.intValue.coerceIn(0, frameCount - 1)
            while (true) {
                frameIndex.intValue = index
                delay(if (index == frameCount - 1) LOOP_HOLD_LAST_MILLI else LOOP_FRAME_MILLI)
                index = (index + 1) % frameCount
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center
    ) {
        val currentMap = map ?: return@Box LoadingPlaceholder()
        val currentFrames = frames

        // The loop frame replaces the latest scan while playing
        if (currentFrames != null) {
            LoopFrameImage(currentFrames, frameIndex::intValue, widthKm, gestureScale)
        } else {
            echoes?.let { RadarLayerImage(it.bitmap, it.widthKm, widthKm, gestureScale) }
        }
        RadarLayerImage(
            currentMap.bitmap, currentMap.widthKm, widthKm, gestureScale,
            contentDescription = stringResource(R.string.radar)
        )

        if (currentFrames != null) {
            LoopFrameTime(
                currentFrames, frameIndex::intValue, location, is24Hr,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }

        val label = stringResource(if (isLoopOn) R.string.radar_pause_loop else R.string.radar_play_loop)
        Tooltip(
            label,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            FilledTonalIconButton(onClick = onToggleLoop, shapes = IconButtonDefaults.shapes()) {
                if (isLoopOn && currentFrames == null) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    Symbol(
                        if (isLoopOn) R.drawable.pause_24px else R.drawable.play_arrow_24px,
                        desc = label,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** A layer drawn for [renderedWidthKm], scaled while pinching or until the current width is drawn */
@Composable
private fun RadarLayerImage(
    bitmap: ImageBitmap,
    renderedWidthKm: Double,
    widthKm: Double,
    gestureScale: () -> Float,
    contentDescription: String? = null
) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = (renderedWidthKm / widthKm).toFloat() * gestureScale()
                scaleX = scale
                scaleY = scale
            }
    )
}

@Composable
private fun LoopFrameImage(frames: LoopFrames, frameIndex: () -> Int, widthKm: Double, gestureScale: () -> Float) {
    val frame = frames.frames.getOrNull(frameIndex()) ?: return
    RadarLayerImage(frame.bitmap, frames.widthKm, widthKm, gestureScale)
}

@Composable
private fun LoopFrameTime(
    frames: LoopFrames,
    frameIndex: () -> Int,
    location: Location,
    is24Hr: Boolean,
    modifier: Modifier
) {
    // Formatted once per loop, the same way for every frame: with the date only if the newest is old
    val labels = remember(frames, location, is24Hr) {
        val withDate = isOld(frames.frames.last().scanTimeMilli)
        frames.frames.map { formatScanTime(it.scanTimeMilli, location, is24Hr, withDate) }
    }
    val label = labels.getOrNull(frameIndex()) ?: return
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** An old scan is shown with its date, e.g. while the radar is down */
private fun isOld(scanTimeMilli: Long) = System.currentTimeMillis() - scanTimeMilli > OLD_SCAN_MILLI

/** Scan time in the location's time zone */
private fun formatScanTime(milli: Long, location: Location, is24Hr: Boolean, withDate: Boolean): String =
    if (is24Hr) {
        to24HourTimeString(milli, location.timezone, if (withDate) "MMMdHHmm" else "HH:mm")
    } else {
        to12HourTimeString(milli, location.timezone, if (withDate) "MMMdhmma" else "h:mm a")
    }

/**
 * One finger drags the page (it only moves right, towards closing); two fingers zoom
 */
private fun Modifier.radarPageGestures(
    key: Any,
    onCloseDrag: (Float) -> Unit,
    onCloseDragEnd: (Float) -> Unit,
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var zooming = false
        val velocity = trackHorizontalDrag(
            down,
            // Rightward and not too steep, so vertical swipes don't close the page
            accept = { total -> !zooming && total.x > 0 && abs(total.y) < 2 * total.x },
            onDrag = onCloseDrag,
            onMultiTouch = { event ->
                zooming = true
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                MultiTouch.Handled
            }
        )
        if (velocity != null) onCloseDragEnd(velocity) else if (zooming) onZoomEnd()
    }
}
