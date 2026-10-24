/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.presentation.library.model.LibraryEntry
import ua.acclorite.book_story.presentation.library.model.LibraryTitlePosition
import ua.acclorite.book_story.ui.common.components.common.AsyncCoverImage
import ua.acclorite.book_story.ui.common.components.common.BookCoverFallback
import ua.acclorite.book_story.ui.common.components.common.StyledText

/** The wall is 3×3: nine covers, the same shape as a book each. */
private const val SUB_SHELF_COLUMNS = 3
private const val SUB_SHELF_ROWS = 3

/**
 * A sub-shelf drawn as a 3×3 wall of the covers inside it, in one grid cell.
 *
 * The tile used to be two offset sheets with a single cover in front. At shelf distance that reads
 * as one book with a shadow — the only hint that it held more was the count badge. Nine covers say
 * it without a label, and the empty slots keep the wall honest when the sub-shelf holds fewer.
 *
 * The cell is 2:3, so each of the nine slots is 2:3 as well: the same shape as a book cover.
 */
@Composable
fun LibrarySubShelfGridTile(
    entry: LibraryEntry.SubShelfEntry,
    titlePosition: LibraryTitlePosition,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.medium
    val slotShape = RoundedCornerShape(3.dp)

    Column(
        modifier
            .padding(3.dp)
            .clip(MaterialTheme.shapes.large)
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f / 1.5f)
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onEdit
                )
                .padding(2.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(SUB_SHELF_ROWS) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(SUB_SHELF_COLUMNS) { column ->
                            SubShelfCoverSlot(
                                book = entry.previews
                                    .getOrNull(row * SUB_SHELF_COLUMNS + column)
                                    ?.data,
                                shape = slotShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }

            if (titlePosition == LibraryTitlePosition.INSIDE) {
                StyledText(
                    text = entry.category.title,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.scrim.copy(0.45f))
                        .padding(6.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(0.9f)
                    ),
                    maxLines = 2
                )
            }

            // Count badge, bottom-end. It covers the corner of the ninth cover, which is the trade
            // for not spending a whole row of the tile on a label.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.tertiary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiary
                )
                StyledText(
                    text = entry.bookCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onTertiary
                    ),
                    maxLines = 1
                )
            }
        }

        if (titlePosition == LibraryTitlePosition.BELOW) {
            Spacer(modifier = Modifier.height(6.dp))
            StyledText(
                text = entry.category.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                ),
                minLines = 2,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

/**
 * One cell of the wall: the cover, or the colour-and-title stand-in, or an empty recess.
 *
 * The empty slot is a tone of its own rather than transparent, so a sub-shelf of two books still
 * shows a 3×3 shape instead of a lone pair of covers floating in the cell.
 */
@Composable
private fun SubShelfCoverSlot(
    book: Book?,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val cover = book?.coverImage
        when {
            cover != null -> AsyncCoverImage(
                uri = cover,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            )

            book != null -> BookCoverFallback(
                title = book.title,
                author = book.author.asString(),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The same folder as a list row: a small stack on the left, name and count on the right. */
@Composable
fun LibrarySubShelfListItem(
    entry: LibraryEntry.SubShelfEntry,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.small
    val cover = entry.previews.firstOrNull()?.data

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onEdit
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .height(72.dp)
                .width(48.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 5.dp, top = 5.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = 5.dp, bottom = 5.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                if (cover?.coverImage != null) {
                    AsyncCoverImage(
                        uri = cover.coverImage,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape)
                    )
                } else {
                    BookCoverFallback(
                        title = entry.category.title,
                        author = "",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            StyledText(
                text = entry.category.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            StyledText(
                text = stringResource(id = R.string.sub_shelf_book_count, entry.bookCount),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1
            )
        }
    }
}
