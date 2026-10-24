/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ua.acclorite.book_story.presentation.library.model.LibraryEntry
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.data.ScrollbarData

@Composable
fun LibraryListLayout(
    entries: List<LibraryEntry>,
    dragState: LibraryDragState,
    dragEnabled: Boolean,
    onDrop: (LibraryDragState.Drop) -> Unit,
    itemContent: @Composable (entry: LibraryEntry) -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var container by remember { mutableStateOf(Rect.Zero) }

    LibraryDragAutoScroll(
        dragState = dragState,
        container = container,
        edge = with(density) { 88.dp.toPx() },
        step = with(density) { 16.dp.toPx() }
    ) { listState.scrollBy(it) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { container = it.boundsInRoot() }
    ) {
        LazyColumnWithScrollbar(
            modifier = Modifier.fillMaxSize(),
            state = listState,
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
                                scaleX = 1.02f
                                scaleY = 1.02f
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
