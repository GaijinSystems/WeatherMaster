package com.pranshulgg.weather_master_app.feature.radar

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Position of the full-width radar panel that slides in from the right
 * [closedFraction] is 0 when fully open and 1 when fully off screen
 */
@Stable
class RadarPanelState(private val scope: CoroutineScope) {

    var closedFraction by mutableFloatStateOf(1f)
        private set

    var isOpen by mutableStateOf(false)
        private set

    var widthPx = 1f

    val isVisible: Boolean get() = closedFraction < 1f

    private var animation: Job? = null

    fun open() = animateTo(0f)

    fun close() = animateTo(1f)

    fun snapClosed() {
        animation?.cancel()
        isOpen = false
        closedFraction = 1f
    }

    fun dragBy(deltaPx: Float) {
        animation?.cancel()
        closedFraction = (closedFraction + deltaPx / widthPx).coerceIn(0f, 1f)
    }

    fun settle(velocityPxPerSecond: Float) {
        val target = when {
            velocityPxPerSecond < -FLING_VELOCITY -> 0f
            velocityPxPerSecond > FLING_VELOCITY -> 1f
            closedFraction < 0.5f -> 0f
            else -> 1f
        }
        animateTo(target, velocityPxPerSecond / widthPx)
    }

    private fun animateTo(target: Float, initialVelocity: Float = 0f) {
        isOpen = target == 0f
        animation?.cancel()
        animation = scope.launch {
            animate(closedFraction, target, initialVelocity, spring(stiffness = 600f)) { value, _ ->
                closedFraction = value
            }
        }
    }

    private companion object {
        const val FLING_VELOCITY = 1000f
    }
}

@Composable
fun rememberRadarPanelState(): RadarPanelState {
    val scope = rememberCoroutineScope()
    return remember { RadarPanelState(scope) }
}

/**
 * Opens the radar panel with a left swipe. Right swipes and drags already taken by children
 * (e.g. the hourly forecast row) are left alone, so the locations drawer keeps working
 */
fun Modifier.radarOpenSwipe(state: RadarPanelState, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            var total = Offset.Zero
            var dragging = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                if (!dragging) {
                    if (change.isConsumed || event.changes.size > 1) break
                    total += change.positionChange()
                    if (total.getDistance() > viewConfiguration.touchSlop) {
                        if (total.x >= 0 || abs(total.x) <= abs(total.y)) break
                        dragging = true
                        state.dragBy(total.x)
                        change.consume()
                    }
                } else {
                    state.dragBy(change.positionChange().x)
                    change.consume()
                }
                velocityTracker.addPosition(change.uptimeMillis, change.position)
            }

            if (dragging) state.settle(velocityTracker.calculateVelocity().x)
        }
    }
