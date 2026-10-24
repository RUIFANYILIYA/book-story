/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.reader.ReaderText
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.common.components.common.SearchTextField
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.components.modal_bottom_sheet.ModalBottomSheet
import ua.acclorite.book_story.ui.common.data.ScrollbarData
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable

private const val MAX_SEARCH_RESULTS = 300

private data class ReaderSearchResult(
    val itemIndex: Int,
    val chapterTitle: String?,
    val snippet: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSearchBottomSheet(
    text: List<ReaderText>,
    dismissBottomSheet: (ReaderEvent.OnDismissBottomSheet) -> Unit,
    scrollToPosition: (ReaderEvent.OnScrollToPosition) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ReaderSearchResult>>(emptyList()) }
    var searchDone by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // The field takes the keyboard itself: the sheet is the last thing opened, so the
    // next thing the user does is type.
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    LaunchedEffect(query, text) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            searchDone = false
            return@LaunchedEffect
        }

        delay(200)
        results = withContext(Dispatchers.Default) {
            searchInBook(text = text, query = trimmed)
        }
        searchDone = true
    }

    ModalBottomSheet(
        hasFixedHeight = true,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f),
        onDismissRequest = {
            dismissBottomSheet(ReaderEvent.OnDismissBottomSheet)
        },
        sheetGesturesEnabled = false
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StyledText(
                    text = stringResource(id = R.string.full_text_search),
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SearchTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                initialQuery = query,
                onQueryChange = { query = it },
                onSearch = {}
            )

            Spacer(modifier = Modifier.height(12.dp))

            StyledText(
                text = when {
                    query.isBlank() -> stringResource(id = R.string.search_in_book_hint)
                    !searchDone -> ""
                    results.isEmpty() -> stringResource(id = R.string.search_no_results)
                    else -> stringResource(id = R.string.search_results, results.size)
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1
            )

            LazyColumnWithScrollbar(
                modifier = Modifier.fillMaxSize(),
                scrollbarSettings = ScrollbarData.secondaryScrollbar
            ) {
                items(
                    count = results.size,
                    key = { index -> "${results[index].itemIndex}-$index" }
                ) { index ->
                    val result = results[index]

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .noRippleClickable {
                                scrollToPosition(
                                    ReaderEvent.OnScrollToPosition(
                                        index = result.itemIndex,
                                        offset = 0
                                    )
                                )
                                dismissBottomSheet(ReaderEvent.OnDismissBottomSheet)
                            }
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        result.chapterTitle?.let { chapterTitle ->
                            StyledText(
                                text = chapterTitle,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 1
                            )
                        }

                        StyledText(
                            text = result.snippet,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 3
                        )
                    }
                }
            }
        }
    }
}

private fun searchInBook(
    text: List<ReaderText>,
    query: String
): List<ReaderSearchResult> {
    val results = mutableListOf<ReaderSearchResult>()
    var chapterTitle: String? = null

    text.forEachIndexed { index, item ->
        when (item) {
            is ReaderText.Chapter -> chapterTitle = item.title

            is ReaderText.Text -> {
                val line = item.line.text
                var from = 0

                while (true) {
                    val at = line.indexOf(query, startIndex = from, ignoreCase = true)
                    if (at < 0) break

                    val snippetStart = (at - 24).coerceAtLeast(0)
                    val snippetEnd = (at + query.length + 48).coerceAtMost(line.length)
                    val snippet = buildString {
                        if (snippetStart > 0) append("…")
                        append(line.substring(snippetStart, snippetEnd).trim())
                        if (snippetEnd < line.length) append("…")
                    }

                    results.add(
                        ReaderSearchResult(
                            itemIndex = index,
                            chapterTitle = chapterTitle,
                            snippet = snippet
                        )
                    )
                    if (results.size >= MAX_SEARCH_RESULTS) return results

                    from = at + query.length
                }
            }

            else -> Unit
        }
    }

    return results
}
