/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ua.acclorite.book_story.domain.model.library.Category
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.ui.common.components.common.StyledText

@Composable
fun LibraryTabs(
    categoriesWithBooks: List<Pair<Category, List<SelectableBook>>>,
    pagerState: PagerState,
    itemCountBackgroundColor: Color,
    showBookCount: Boolean,
    onReorder: (List<Category>) -> Unit
) {
    val scope = rememberCoroutineScope()

    // The synthetic "default" tab is not a shelf and has no order to speak of, so it stays put.
    val firstMovable = if (categoriesWithBooks.firstOrNull()?.first?.id == -1) 1 else 0

    Box(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        ScrollableTabRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 0.5.dp),
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            edgePadding = 0.dp,
            divider = {},
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    val width by animateDpAsState(
                        targetValue = tabPositions[pagerState.currentPage].contentWidth,
                        label = ""
                    )

                    TabRowDefaults.PrimaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        width = width
                    )
                }
            }
        ) {
            categoriesWithBooks.forEachIndexed { index, tabItem ->
                var dragOffset by remember(tabItem.first.id) { mutableFloatStateOf(0f) }
                var dragging by remember(tabItem.first.id) { mutableStateOf(false) }
                val movable = tabItem.first.id != -1 && categoriesWithBooks.size - firstMovable > 1

                Tab(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = dragOffset
                            alpha = if (dragging) 0.85f else 1f
                            scaleX = if (dragging) 1.04f else 1f
                            scaleY = if (dragging) 1.04f else 1f
                        }
                        .then(
                            if (movable) {
                                Modifier.pointerInput(tabItem.first.id, categoriesWithBooks.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = true
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.x
                                        },
                                        onDragEnd = {
                                            // The tab's own width is the yardstick: a tab is as wide
                                            // as its label, so one width of travel is about one slot.
                                            val slot = size.width.toFloat().coerceAtLeast(1f)
                                            val steps = (dragOffset / slot).roundToInt()
                                            val target = (index + steps)
                                                .coerceIn(firstMovable, categoriesWithBooks.lastIndex)
                                            if (target != index) {
                                                val reordered =
                                                    categoriesWithBooks.map { it.first }.toMutableList()
                                                reordered.add(target, reordered.removeAt(index))
                                                onReorder(reordered)
                                            }
                                            dragging = false
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragging = false
                                            dragOffset = 0f
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        ),
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StyledText(
                                text = tabItem.first.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                            )

                            if (showBookCount) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StyledText(
                                    text = tabItem.second.size.toString(),
                                    modifier = Modifier
                                        .background(
                                            itemCountBackgroundColor,
                                            MaterialTheme.shapes.medium
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}