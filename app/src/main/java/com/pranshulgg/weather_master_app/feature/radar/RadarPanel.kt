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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.pranshulgg.weather_master_app.core.utils.formatters.to12HourTimeString
import com.pranshulgg.weather_master_app.core.utils.formatters.to24HourTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MIN_WIDTH_KM = 25.0
private const val DEFAULT_WIDTH_KM = 100.0
private val BORDER_WIDTH = 16.dp // left and right edges of the page
private const val OLD_SCAN_MILLI = 30 * 60 * 1000L

/**
 * Full-width radar page that slides in from the right over the main screen
 * Right swipe, back or the top bar arrow close it; pinch and double-tap zoom around the location
 */
@Composable
fun RadarPanel(state: RadarPanelState, location: Location) {
    val isVisible by remember { derivedStateOf { state.isVisible } }
    if (!isVisible) return

    val viewModel: RadarViewModel = hiltViewModel()
    val uiState = viewModel.uiState
    val scan = uiState.scan?.takeIf { uiState.locationKey == location.radarKey }

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
                error = uiState.error?.takeIf { uiState.locationKey == location.radarKey },
                widthKm = widthKm,
                gestureScale = { gestureScale },
                onRetry = { viewModel.retry(location) },
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
    error: AppException?,
    widthKm: Double,
    gestureScale: () -> Float,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val prefs = LocalAppPrefs.current

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
                            // Include the date for an old scan, e.g. while the radar is down
                            val isOld = System.currentTimeMillis() - scan.scanTimeMilli > OLD_SCAN_MILLI
                            val time = if (prefs.is24HrTimeFormat) {
                                to24HourTimeString(scan.scanTimeMilli, location.timezone, if (isOld) "MMMdHHmm" else "HH:mm")
                            } else {
                                to12HourTimeString(scan.scanTimeMilli, location.timezone, if (isOld) "MMMdhmma" else "h:mm a")
                            }
                            Text(
                                "${scan.station} · $time",
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
                scan != null -> RadarImage(scan, location, widthKm, gestureScale)
                error != null -> ErrorContainer(
                    onRetry = onRetry,
                    errorDescription = stringResource(error.toMessageRes())
                )

                else -> LoadingPlaceholder()
            }
        }
    }
}

@Composable
private fun RadarImage(
    scan: RadarScan,
    location: Location,
    widthKm: Double,
    gestureScale: () -> Float
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
    var rendered by remember { mutableStateOf<Pair<ImageBitmap, Double>?>(null) }

    LaunchedEffect(scan, outlines, widthKm, size, colors) {
        val loadedOutlines = outlines ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) {
            RadarRenderer.render(
                scan = scan,
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
        rendered = bitmap.asImageBitmap() to widthKm
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center
    ) {
        rendered?.let { (bitmap, renderedWidthKm) ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.radar),
                contentScale = ContentScale.None,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Scale the last bitmap while pinching or until the new zoom is drawn
                        val scale = (renderedWidthKm / widthKm).toFloat() * gestureScale()
                        scaleX = scale
                        scaleY = scale
                    }
            )
        } ?: LoadingPlaceholder()
    }
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
