/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ua.acclorite.book_story.presentation.library.model.LibraryEntry
import ua.acclorite.book_story.ui.common.components.common.LazyVerticalGridWithScrollbar
import ua.acclorite.book_story.ui.common.data.ScrollbarData

@Composable
fun LibraryGridLayout(
    entries: List<LibraryEntry>,
    gridSize: Int,
    autoGridSize: Boolean,
    dragState: LibraryDragState,
    dragEnabled: Boolean,
    onDrop: (LibraryDragState.Drop) -> Unit,
    itemContent: @Composable (entry: LibraryEntry) -> Unit
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    var container by remember { mutableStateOf(Rect.Zero) }

    LibraryDragAutoScroll(
        dragState = dragState,
        container = container,
        edge = with(density) { 88.dp.toPx() },
        step = with(density) { 16.dp.toPx() }
    ) { gridState.scrollBy(it) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { container = it.boundsInRoot() }
    ) {
        LazyVerticalGridWithScrollbar(
            columns = if (autoGridSize) GridCells.Adaptive(120.dp)
            else GridCells.Fixed(gridSize.coerceAtLeast(1)),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            scrollbarSettings = ScrollbarData.primaryScrollbar,
            contentPadding = PaddingValues(8.dp)
        ) {
            items(
                entries,
                key = { it.key }
            ) { entry ->
                val dragging = dragState.draggingKey == entry.key
                val hovered = dragState.hoveredKey == entry.key

                DisposableEffect(entry.key) {
                    onDispose { dragState.forget(entry.key) }
                }

                // The gesture node stays where the cell is; only the copy inside it moves. Putting
                // the translation on the same node that reads the pointer would move the coordinate
                // space under the finger and the drag would cancel itself out.
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .then(
                            if (dragEnabled) {
                                Modifier.libraryDragSource(
                                    dragState = dragState,
                                    key = entry.key,
                                    enabled = true,
                                    onDrop = onDrop
                                )
                            } else {
                                Modifier
                            }
                        )
                        .animateItem()
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            if (dragging) {
                                translationX = dragState.dragOffset.x
                                translationY = dragState.dragOffset.y
                                scaleX = 1.04f
                                scaleY = 1.04f
                                alpha = 0.9f
                            }
                        }
                    ) {
                        LibraryDragHighlight(highlighted = hovered) {
                            itemContent(entry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keeps scrolling while a dragged cell hovers near the top or bottom edge, so a book can be filed
 * onto a target that started off-screen.
 */
@Composable
fun LibraryDragAutoScroll(
    dragState: LibraryDragState,
    container: Rect,
    edge: Float,
    step: Float,
    scroll: suspend (Float) -> Unit
) {
    LaunchedEffect(dragState.draggingKey, container) {
        if (dragState.draggingKey == null || container.isEmpty) return@LaunchedEffect

        while (true) {
            val y = dragState.pointer.y
            val delta = when {
                y < container.top + edge -> -step
                y > container.bottom - edge -> step
                else -> 0f
            }
            if (delta != 0f) scroll(delta)
            withFrameNanos { }
        }
    }
}

/** Marks the cell a dragged book would land on: a ring, plus a lift so it reads as "this one". */
@Composable
fun LibraryDragHighlight(
    highlighted: Boolean,
    content: @Composable () -> Unit
) {
    val ring = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent

    Box(
        Modifier
            .graphicsLayer {
                if (highlighted) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                }
            }
            .border(
                width = if (highlighted) 2.dp else 0.dp,
                color = ring,
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        content()
    }
}
