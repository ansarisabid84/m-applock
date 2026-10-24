package com.sabid.applock.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sabid.applock.service.LockLog
import kotlin.math.hypot

/** Shortest pattern we accept, matching the platform lock screen. */
const val MIN_NODES = 4

/**
 * 3x3 pattern input.
 *
 * [invisible] draws nothing at all while the finger moves — the shoulder-surfing
 * defence, since an onlooker sees no trail to memorise.
 */
@Composable
fun PatternLock(
    modifier: Modifier = Modifier,
    maxSize: Dp = 320.dp,
    accent: Color,
    idle: Color,
    invisible: Boolean = false,
    error: Boolean = false,
    onComplete: (List<Int>) -> Unit
) {
    val selected = remember { mutableStateListOf<Int>() }
    var pointer by remember { mutableStateOf<Offset?>(null) }

    fun nodeCentres(size: Float): List<Offset> {
        val step = size / 4f
        return (0..8).map { i -> Offset(step * (i % 3 + 1), step * (i / 3 + 1)) }
    }

    Box(modifier = modifier.widthIn(max = maxSize).fillMaxWidth().aspectRatio(1f).padding(12.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(invisible) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            selected.clear()
                            pointer = offset
                            hit(offset, nodeCentres(size.width.toFloat()), size.width / 8f)
                                ?.let { selected.add(it) }
                        },
                        onDrag = { change, _ ->
                            pointer = change.position
                            hit(change.position, nodeCentres(size.width.toFloat()), size.width / 8f)
                                ?.let { if (it !in selected) selected.add(it) }
                        },
                        onDragEnd = {
                            pointer = null
                            // Only a pattern of at least MIN_NODES counts as an attempt.
                            // Emitting shorter ones let a stray tap register as a real
                            // guess: it burned a retry and, during setup, could store a
                            // one-dot "pattern" as the user's secret.
                            if (selected.size >= MIN_NODES) {
                                onComplete(selected.toList())
                            } else if (selected.isNotEmpty()) {
                                // Only ever logged for gestures too short to be an attempt, so
                                // this can never describe a pattern that actually unlocks
                                // anything. It is here because "I drew a pattern and nothing
                                // happened" is otherwise indistinguishable from a dead input.
                                LockLog.d("pattern ignored: ${selected.size} of $MIN_NODES nodes")
                            }
                            selected.clear()
                        },
                        onDragCancel = { pointer = null; selected.clear() }
                    )
                }
        ) {
            val centres = nodeCentres(size.width)
            val radius = size.width / 26f

            centres.forEachIndexed { index, centre ->
                val active = !invisible && index in selected
                drawCircle(
                    color = if (active) accent else idle.copy(alpha = 0.5f),
                    radius = if (active) radius * 1.5f else radius,
                    center = centre,
                    style = if (active) Stroke(width = radius * 0.5f) else Stroke(width = radius * 0.35f)
                )
                if (active) drawCircle(accent, radius * 0.55f, centre)
            }

            if (!invisible && selected.isNotEmpty()) {
                val stroke = Stroke(width = size.width / 90f)
                val line = if (error) Color(0xFFE07A70) else accent
                for (i in 0 until selected.size - 1) {
                    drawLine(line, centres[selected[i]], centres[selected[i + 1]], stroke.width)
                }
                pointer?.let { drawLine(line, centres[selected.last()], it, stroke.width) }
            }
        }
    }
}

private fun hit(point: Offset, centres: List<Offset>, tolerance: Float): Int? =
    centres.indexOfFirst { hypot(it.x - point.x, it.y - point.y) <= tolerance }
        .takeIf { it >= 0 }
