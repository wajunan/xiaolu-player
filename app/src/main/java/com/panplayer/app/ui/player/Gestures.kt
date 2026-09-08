package com.panplayer.app.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.hypot

enum class DragType { SEEK, BRIGHTNESS, VOLUME }

class GestureCallbacks(
    val onTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val positionProvider: () -> Long,
    val durationProvider: () -> Long,
    val brightnessProvider: () -> Float,
    val volumeProvider: () -> Float,
    val onSeekDrag: (started: Boolean, targetMs: Long, ended: Boolean) -> Unit,
    val onBrightnessDrag: (started: Boolean, value: Float, ended: Boolean) -> Unit,
    val onVolumeDrag: (started: Boolean, value: Float, ended: Boolean) -> Unit,
    val onPinch: (scaleFactor: Float) -> Unit
)

fun Modifier.playerGestures(callbacks: GestureCallbacks): Modifier {
    return this
        .tapLayer(callbacks)
        .dragLayer(callbacks)
}

private fun Modifier.tapLayer(c: GestureCallbacks): Modifier = pointerInput(c) {
    detectTapGestures(
        onTap = { c.onTap() },
        onDoubleTap = { c.onDoubleTap() }
    )
}

private fun Modifier.dragLayer(c: GestureCallbacks): Modifier = pointerInput(c) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = down.position
        var type = DragType.SEEK
        var started = false
        var pinchActive = false
        var lastDist = 0f
        var totalDx = 0f
        var lastPos: Offset = start
        var lastTarget = 0L

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            if (pressed.size >= 2) {
                if (!pinchActive) {
                    pinchActive = true
                    started = false
                    totalDx = 0f
                }
                val a = pressed[0].position
                val b = pressed[1].position
                val dist = hypot(b.x - a.x, b.y - a.y)
                if (lastDist > 0f && dist > 0f) {
                    c.onPinch(dist / lastDist)
                }
                lastDist = dist
            } else if (pressed.size == 1) {
                val p = pressed[0]
                if (!pinchActive) {
                    val dx = p.position.x - start.x
                    val dy = p.position.y - start.y
                    if (!started) {
                        if (abs(dx) > slop || abs(dy) > slop) {
                            started = true
                            type = if (abs(dx) > abs(dy)) {
                                DragType.SEEK
                            } else if (start.x < size.width * 0.5f) {
                                DragType.BRIGHTNESS
                            } else {
                                DragType.VOLUME
                            }
                            when (type) {
                                DragType.SEEK -> c.onSeekDrag(true, c.positionProvider(), false)
                                DragType.BRIGHTNESS -> c.onBrightnessDrag(true, c.brightnessProvider(), false)
                                DragType.VOLUME -> c.onVolumeDrag(true, c.volumeProvider(), false)
                            }
                            lastPos = p.position
                        }
                    } else {
                        totalDx += p.position.x - lastPos.x
                        lastPos = p.position
                        when (type) {
                            DragType.SEEK -> {
                                val duration = c.durationProvider().coerceAtLeast(0L)
                                val span = (duration / 3).coerceIn(60_000L, 600_000L)
                                val base = c.positionProvider()
                                val target = (base + (totalDx / size.width.toFloat() * span.toFloat()).toLong())
                                    .coerceIn(0L, duration)
                                lastTarget = target
                                c.onSeekDrag(true, target, false)
                            }
                            DragType.BRIGHTNESS -> {
                                val value = (c.brightnessProvider() + (start.y - p.position.y) / size.height)
                                    .coerceIn(0f, 1f)
                                c.onBrightnessDrag(true, value, false)
                            }
                            DragType.VOLUME -> {
                                val value = (c.volumeProvider() + (start.y - p.position.y) / size.height)
                                    .coerceIn(0f, 1f)
                                c.onVolumeDrag(true, value, false)
                            }
                        }
                    }
                }
            }

            event.changes.forEach { change ->
                if (started || pinchActive) change.consume()
            }

            if (event.changes.all { !it.pressed }) break
        }

        if (started && !pinchActive) {
            when (type) {
                DragType.SEEK -> c.onSeekDrag(false, lastTarget, true)
                DragType.BRIGHTNESS -> c.onBrightnessDrag(false, 0f, true)
                DragType.VOLUME -> c.onVolumeDrag(false, 0f, true)
            }
        }
    }
}
