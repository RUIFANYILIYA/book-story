/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.runtime.Composable
import ua.acclorite.book_story.presentation.library.model.LibraryEntry
import ua.acclorite.book_story.presentation.library.model.LibraryLayout

@Composable
fun LibraryLayout(
    entries: List<LibraryEntry>,
    layout: LibraryLayout,
    gridSize: Int,
    autoGridSize: Boolean,
    dragState: LibraryDragState,
    dragEnabled: Boolean,
    onDrop: (LibraryDragState.Drop) -> Unit,
    itemContent: @Composable (entry: LibraryEntry) -> Unit
) {
    when (layout) {
        LibraryLayout.LIST -> {
            LibraryListLayout(
                entries = entries,
                dragState = dragState,
                dragEnabled = dragEnabled,
                onDrop = onDrop,
                itemContent = itemContent
            )
        }

        LibraryLayout.GRID -> {
            LibraryGridLayout(
                entries = entries,
                gridSize = gridSize,
                autoGridSize = autoGridSize,
                dragState = dragState,
                dragEnabled = dragEnabled,
                onDrop = onDrop,
                itemContent = itemContent
            )
        }
    }
}
