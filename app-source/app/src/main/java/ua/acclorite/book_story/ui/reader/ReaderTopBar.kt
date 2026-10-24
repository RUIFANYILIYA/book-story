/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.domain.model.reader.Bookmark
import ua.acclorite.book_story.domain.model.reader.ReaderText.Chapter
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.settings.SettingsEvent
import ua.acclorite.book_story.ui.common.components.common.IconButton
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalActivity
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderTopBar(
    book: Book,
    currentChapter: Chapter?,
    fastColorPresetChange: Boolean,
    currentChapterProgress: Float,
    isLoading: Boolean,
    lockMenu: Boolean,
    backgroundColor: Color,
    fontColor: Color,
    bookmarkAtPosition: Bookmark?,
    leave: (ReaderEvent.OnLeave) -> Unit,
    switchColorPreset: (SettingsEvent.OnSwitchColorPreset) -> Unit,
    toggleBookmark: (ReaderEvent.OnToggleBookmark) -> Unit,
    showSearchBottomSheet: (ReaderEvent.OnShowSearchBottomSheet) -> Unit,
    navigateToBookInfo: (ReaderEvent.OnNavigateToBookInfo) -> Unit,
    navigateBack: (ReaderEvent.OnNavigateBack) -> Unit
) {
    val activity = LocalActivity.current
    val animatedChapterProgress = animateFloatAsState(
        targetValue = currentChapterProgress
    )
    var optionsExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .readerColorPresetChange(
                colorPresetChangeEnabled = fastColorPresetChange,
                isLoading = isLoading,
                switchColorPreset = switchColorPreset
            )
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = R.string.go_back_content_desc,
                    disableOnClick = true,
                    color = fontColor
                ) {
                    leave(
                        ReaderEvent.OnLeave(
                            navigate = {
                                navigateBack(ReaderEvent.OnNavigateBack)
                            }
                        )
                    )
                }
            },
            title = {
                StyledText(
                    text = book.title,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .noRippleClickable(
                            enabled = !lockMenu,
                            onClick = {
                                leave(
                                    ReaderEvent.OnLeave(
                                        navigate = {
                                            navigateToBookInfo(
                                                ReaderEvent.OnNavigateToBookInfo(
                                                    changePath = false
                                                )
                                            )
                                        }
                                    )
                                )
                            }
                        ),
                    style = LocalTextStyle.current.copy(
                        fontSize = 20.sp,
                        lineHeight = 20.sp,
                        color = fontColor
                    ),
                    maxLines = 1
                )
            },
            subtitle = {
                StyledText(
                    text = currentChapter?.title
                        ?: activity.getString(R.string.no_chapters),
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = fontColor.copy(alpha = 0.7f)
                    ),
                    maxLines = 1
                )
            },
            actions = {
                Box {
                    IconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = R.string.reader_more_options_content_desc,
                        disableOnClick = false,
                        enabled = !lockMenu,
                        color = fontColor
                    ) {
                        optionsExpanded = true
                    }

                    DropdownMenu(
                        expanded = optionsExpanded,
                        onDismissRequest = { optionsExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                StyledText(
                                    text = stringResource(
                                        id = if (bookmarkAtPosition != null) R.string.remove_bookmark
                                        else R.string.add_bookmark
                                    ),
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (bookmarkAtPosition != null)
                                        Icons.Outlined.BookmarkAdded
                                    else Icons.Outlined.BookmarkAdd,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                optionsExpanded = false
                                toggleBookmark(ReaderEvent.OnToggleBookmark)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                StyledText(
                                    text = stringResource(id = R.string.full_text_search),
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                optionsExpanded = false
                                showSearchBottomSheet(ReaderEvent.OnShowSearchBottomSheet)
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        if (currentChapter != null) {
            LinearProgressIndicator(
                progress = { animatedChapterProgress.value },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
