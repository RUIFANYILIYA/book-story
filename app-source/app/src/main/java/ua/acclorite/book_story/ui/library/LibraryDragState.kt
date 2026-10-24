/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Drag state for "drop one book onto another to make a sub-shelf".
 *
 * The hit test deliberately does not read the lazy grid's `layoutInfo`: doing it that way would
 * tie the gesture to one layout implementation, and the library has two (grid and list). Instead
 * every cell registers its own bounds in root coordinates, so the same gesture works in both and
 * survives a layout switch mid-session.
 *
 * Only active while the library is in arrange mode. Long-press is the drag handle there; outside
 * of it long-press still opens the book actions sheet.
 */
@Stable
class LibraryDragState {

    /** Key of the cell under the finger, or `null` when nothing is being dragged. */
    var draggingKey by mutableStateOf<String?>(null)
        private set

    /** How far the dragged cell has travelled, for its floating copy. */
    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    /** Cell the finger is over right now — the drop target, highlighted while dragging. */
    var hoveredKey by mutableStateOf<String?>(null)
        private set

    /** Finger position in root coordinates; the layouts use it to auto-scroll at the edges. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    private val bounds = HashMap<String, Rect>()

    fun updateBounds(key: String, rect: Rect) {
        bounds[key] = rect
    }

    fun forget(key: String) {
        bounds.remove(key)
    }

    fun start(key: String, local: Offset) {
        val rect = bounds[key] ?: return
        draggingKey = key
        dragOffset = Offset.Zero
        pointer = rect.topLeft + local
        hoveredKey = null
    }

    fun drag(delta: Offset) {
        if (draggingKey == null) return
        dragOffset += delta
        pointer += delta
        hoveredKey = targetAt(pointer)
    }

    /** Ends the gesture and reports what landed on what, if anything did. */
    fun finish(): Drop? {
        val dragged = draggingKey
        val target = hoveredKey
        cancel()
        return if (dragged != null && target != null && target != dragged) Drop(dragged, target) else null
    }

    fun cancel() {
        draggingKey = null
        hoveredKey = null
        dragOffset = Offset.Zero
    }

    private fun targetAt(point: Offset): String? {
        var best: String? = null
        var bestDistance = Float.MAX_VALUE

        for ((key, rect) in bounds) {
            if (key == draggingKey) continue
            if (!rect.contains(point)) continue

            // Overlapping cells (a finger between two of them) go to the nearer centre.
            val dx = rect.center.x - point.x
            val dy = rect.center.y - point.y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = key
            }
        }

        return best
    }

    data class Drop(
        val draggedKey: String,
        val targetKey: String
    )
}

/**
 * Makes a cell a drag source while [enabled].
 *
 * [onDrop] only fires when the finger came to rest on another cell; letting go over empty space
 * is a cancel, which is what people expect from a drag that missed.
 */
fun Modifier.libraryDragSource(
    dragState: LibraryDragState,
    key: String,
    enabled: Boolean,
    onDrop: (LibraryDragState.Drop) -> Unit
): Modifier = this
    .onGloballyPositioned {
        if (enabled) dragState.updateBounds(key, it.boundsInRoot())
    }
    .pointerInput(key, enabled) {
        if (!enabled) return@pointerInput

        detectDragGesturesAfterLongPress(
            onDragStart = { local -> dragState.start(key, local) },
            onDrag = { change, amount ->
                change.consume()
                dragState.drag(amount)
            },
            onDragEnd = { dragState.finish()?.let(onDrop) },
            onDragCancel = { dragState.cancel() }
        )
    }
