package com.pranshulgg.weather_master_app.feature.radar

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pranshulgg.weather_master_app.R
import com.pranshulgg.weather_master_app.core.model.domain.location.Location
import com.pranshulgg.weather_master_app.core.model.domain.radar.RadarScan
import com.pranshulgg.weather_master_app.core.prefs.LocalAppPrefs
import com.pranshulgg.weather_master_app.core.ui.components.Symbol
import com.pranshulgg.weather_master_app.core.utils.formatters.to12HourTimeString
import com.pranshulgg.weather_master_app.core.utils.formatters.to24HourTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.time.Duration.Companion.minutes

private const val MIN_WIDTH_KM = 25.0
private const val DEFAULT_WIDTH_KM = 100.0
private val REFRESH_INTERVAL = 3.minutes

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
    val scan = uiState.scan?.takeIf { uiState.locationId == location.id }

    LaunchedEffect(location.id) {
        while (true) {
            viewModel.refresh(location)
            delay(REFRESH_INTERVAL)
        }
    }

    var widthKm by rememberSaveable(location.id) { mutableStateOf(DEFAULT_WIDTH_KM) }
    var gestureScale by remember { mutableFloatStateOf(1f) }
    val maxWidthKm = scan?.let { 2 * (it.binCount * it.binSizeKm + distanceKm(location, it)) } ?: 1000.0
    val currentMaxWidthKm by rememberUpdatedState(maxWidthKm)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = state.closedFraction * size.width }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    val presets = listOf(MIN_WIDTH_KM, DEFAULT_WIDTH_KM, currentMaxWidthKm)
                    val current = presets.indices.minBy { abs(presets[it] - widthKm) }
                    widthKm = presets[(current + 1) % presets.size]
                })
            }
            .radarPageGestures(
                onCloseDrag = state::dragBy,
                onCloseDragEnd = state::settle,
                onZoom = { zoom ->
                    gestureScale = (gestureScale * zoom)
                        .coerceIn((widthKm / currentMaxWidthKm).toFloat(), (widthKm / MIN_WIDTH_KM).toFloat())
                },
                onZoomEnd = {
                    widthKm = (widthKm / gestureScale).coerceIn(MIN_WIDTH_KM, currentMaxWidthKm)
                    gestureScale = 1f
                }
            )
    ) {
        RadarPage(
            location = location,
            uiState = uiState,
            scan = scan,
            widthKm = widthKm,
            gestureScale = { gestureScale },
            onClose = state::close
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadarPage(
    location: Location,
    uiState: RadarUiState,
    scan: RadarScan?,
    widthKm: Double,
    gestureScale: () -> Float,
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
                            val time = if (prefs.is24HrTimeFormat) {
                                to24HourTimeString(scan.scanTimeMilli, location.timezone, "HH:mm")
                            } else {
                                to12HourTimeString(scan.scanTimeMilli, location.timezone, "h:mm a")
                            }
                            Text(
                                "${scan.station} · $time",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Symbol(
                            R.drawable.arrow_back_24px,
                            desc = "arrow back icon",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                scan != null -> RadarImage(scan, location, widthKm, gestureScale)
                uiState.isUnavailable -> Text(
                    stringResource(R.string.radar_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
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
            .onSizeChanged { size = it }
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
        } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

private enum class GestureMode { Undecided, CloseDrag, Zoom, Ignored }

/**
 * One finger dragging right closes the page (left does nothing); two fingers zoom
 */
private fun Modifier.radarPageGestures(
    onCloseDrag: (Float) -> Unit,
    onCloseDragEnd: (Float) -> Unit,
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var mode = GestureMode.Undecided

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break

            if (mode != GestureMode.CloseDrag && event.changes.count { it.pressed } >= 2) {
                mode = GestureMode.Zoom
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                continue
            }

            val change = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: continue
            when (mode) {
                GestureMode.Undecided -> {
                    total += change.positionChange()
                    if (total.getDistance() > viewConfiguration.touchSlop) {
                        if (total.x > 0 && abs(total.x) > abs(total.y)) {
                            mode = GestureMode.CloseDrag
                            onCloseDrag(total.x)
                            change.consume()
                        } else {
                            mode = GestureMode.Ignored
                        }
                    }
                }

                GestureMode.CloseDrag -> {
                    onCloseDrag(change.positionChange().x)
                    change.consume()
                }

                else -> Unit
            }
            velocityTracker.addPosition(change.uptimeMillis, change.position)
        }

        when (mode) {
            GestureMode.CloseDrag -> onCloseDragEnd(velocityTracker.calculateVelocity().x)
            GestureMode.Zoom -> onZoomEnd()
            else -> Unit
        }
    }
}

private fun distanceKm(location: Location, scan: RadarScan): Double {
    val east = (scan.longitude - location.longitude) * cos(Math.toRadians(location.latitude)) * 111.2
    val north = (scan.latitude - location.latitude) * 111.2
    return hypot(east, north)
}
