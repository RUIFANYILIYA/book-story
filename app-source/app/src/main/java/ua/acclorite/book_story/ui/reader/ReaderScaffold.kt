/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import android.annotation.SuppressLint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import ua.acclorite.book_story.core.helpers.calculateProgress
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.domain.model.reader.Bookmark
import ua.acclorite.book_story.domain.model.reader.ReaderText
import ua.acclorite.book_story.domain.model.reader.ReaderText.Chapter
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.reader.model.ReaderFontThickness
import ua.acclorite.book_story.presentation.reader.model.ReaderHorizontalGesture
import ua.acclorite.book_story.presentation.reader.model.ReaderReadingMode
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.presentation.settings.SettingsEvent
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.reader.model.FontWithName
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment
import kotlinx.coroutines.launch

/** Matches the horizontal gesture's page turn, so both feel like the same motion. */
private const val VOLUME_KEY_PAGE_TURN_DURATION = 250

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ReaderScaffold(
    book: Book,
    text: List<ReaderText>,
    chapters: List<Chapter>,
    listState: LazyListState,
    readingMode: ReaderReadingMode,
    currentChapter: Chapter?,
    nestedScrollConnection: NestedScrollConnection,
    fastColorPresetChange: Boolean,
    perceptionExpander: Boolean,
    perceptionExpanderPadding: Dp,
    perceptionExpanderThickness: Dp,
    currentChapterProgress: Float,
    isLoading: Boolean,
    bookmarks: List<Bookmark>,
    bookmarkAtPosition: Bookmark?,
    showMenu: Boolean,
    lockMenu: Boolean,
    contentPadding: PaddingValues,
    verticalPadding: Dp,
    volumeKeyPaging: Boolean,
    horizontalGesture: ReaderHorizontalGesture,
    horizontalGestureScroll: Float,
    horizontalGestureSensitivity: Dp,
    horizontalGestureAlphaAnim: Boolean,
    horizontalGesturePullAnim: Boolean,
    highlightedReading: Boolean,
    highlightedReadingThickness: FontWeight,
    progress: String,
    progressBar: Boolean,
    progressBarPadding: Dp,
    progressBarAlignment: HorizontalAlignment,
    progressBarFontSize: TextUnit,
    paragraphHeight: Dp,
    sidePadding: Dp,
    bottomBarPadding: Dp,
    backgroundColor: Color,
    fontColor: Color,
    images: Boolean,
    imagesCaptions: Boolean,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesColorEffects: ColorFilter?,
    fontFamily: FontWithName,
    lineHeight: TextUnit,
    fontThickness: ReaderFontThickness,
    fontStyle: FontStyle,
    chapterTitleAlignment: ReaderTextAlignment,
    textAlignment: ReaderTextAlignment,
    horizontalAlignment: Alignment.Horizontal,
    fontSize: TextUnit,
    letterSpacing: TextUnit,
    paragraphIndentation: TextUnit,
    doubleClickTranslation: Boolean,
    switchColorPreset: (SettingsEvent.OnSwitchColorPreset) -> Unit,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    leave: (ReaderEvent.OnLeave) -> Unit,
    scrollToChapter: (ReaderEvent.OnScrollToChapter) -> Unit,
    scrollToPosition: (ReaderEvent.OnScrollToPosition) -> Unit,
    toggleBookmark: (ReaderEvent.OnToggleBookmark) -> Unit,
    changeProgress: (ReaderEvent.OnChangeProgress) -> Unit,
    updateChapter: (ReaderEvent.OnUpdateChapter) -> Unit,
    openShareApp: (ReaderEvent.OnOpenShareApp) -> Unit,
    openWebBrowser: (ReaderEvent.OnOpenWebBrowser) -> Unit,
    openTranslator: (ReaderEvent.OnOpenTranslator) -> Unit,
    openDictionary: (ReaderEvent.OnOpenDictionary) -> Unit,
    showSettingsBottomSheet: (ReaderEvent.OnShowSettingsBottomSheet) -> Unit,
    showSearchBottomSheet: (ReaderEvent.OnShowSearchBottomSheet) -> Unit,
    showChaptersDrawer: (ReaderEvent.OnShowChaptersDrawer) -> Unit,
    navigateToBookInfo: (ReaderEvent.OnNavigateToBookInfo) -> Unit,
    navigateBack: (ReaderEvent.OnNavigateBack) -> Unit
) {
    val scope = rememberCoroutineScope()

    // The scrolling list reports a viewport height of zero until it has actually been laid out, and
    // a key can arrive before that happens. Measuring the surface ourselves gives a second source,
    // and whichever one is known is used.
    val measuredHeight = remember { mutableFloatStateOf(0f) }
    val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
    val pageHeight = if (viewportHeight > 0f) viewportHeight else measuredHeight.floatValue

    // One press turns as much as the horizontal gesture would: a whole screen in PAGE mode, and the
    // configured fraction in SCROLL mode, so each mode pages by the amount it looks like it should.
    val pageTurnDistance =
        if (readingMode == ReaderReadingMode.PAGE) pageHeight
        else pageHeight * horizontalGestureScroll

    // The reader's own progress string answers to the "progress count" setting and may be counting
    // chapters instead of percent. The bottom bar always wants the percentage, so it is built from
    // the book directly rather than reusing that string.
    val bookProgress = remember(book.progress) {
        "${book.progress.calculateProgress(1)}%"
    }

    // Key events only reach whatever holds focus, and a page of text has nothing focusable to hand
    // them to, so the reading surface takes focus itself. Without this the rocker just changes the
    // volume and the reader never hears about the press.
    // PAGE mode keeps its position in a pager of its own rather than in the list state, so it
    // handles the keys itself; here they only apply to SCROLL mode.
    val volumeKeyPagingHere =
        volumeKeyPaging && !isLoading && readingMode == ReaderReadingMode.SCROLL

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(volumeKeyPagingHere) {
        if (volumeKeyPagingHere) {
            // A node has to exist before it can hold focus, and asking on the first composition
            // fires before layout has attached it. Waiting a frame makes the request land.
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    Scaffold(
        Modifier
            .fillMaxSize()
            .then(
                if (volumeKeyPagingHere) {
                    Modifier.focusRequester(focusRequester).focusable()
                } else Modifier
            )
            .onGloballyPositioned { measuredHeight.floatValue = it.size.height.toFloat() }
            .then(
                if (volumeKeyPagingHere) {
                    Modifier.onPreviewKeyEvent { event ->
                        // Reacting to key-up as well would turn a second page on release, which
                        // reads as an accidental double tap.
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                        // Volume down goes forward: that is what a thumb resting on the rocker
                        // expects, and what other readers do.
                        val distance = when (event.key) {
                            Key.VolumeDown -> pageTurnDistance
                            Key.VolumeUp -> -pageTurnDistance
                            else -> return@onPreviewKeyEvent false
                        }

                        if (distance != 0f) {
                            scope.launch {
                                listState.animateScrollBy(
                                    distance,
                                    tween(
                                        durationMillis = VOLUME_KEY_PAGE_TURN_DURATION,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        }
                        true
                    }
                } else Modifier
            )
            .nestedScroll(nestedScrollConnection),
        containerColor = backgroundColor,
        topBar = {
            AnimatedVisibility(
                visible = showMenu,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it }
            ) {
                ReaderTopBar(
                    book = book,
                    currentChapter = currentChapter,
                    fastColorPresetChange = fastColorPresetChange,
                    currentChapterProgress = currentChapterProgress,
                    isLoading = isLoading,
                    lockMenu = lockMenu,
                    backgroundColor = backgroundColor,
                    fontColor = fontColor,
                    bookmarkAtPosition = bookmarkAtPosition,
                    leave = leave,
                    switchColorPreset = switchColorPreset,
                    toggleBookmark = toggleBookmark,
                    showSearchBottomSheet = showSearchBottomSheet,
                    navigateBack = navigateBack,
                    navigateToBookInfo = navigateToBookInfo
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                modifier = Modifier.fillMaxWidth(),
                visible = showMenu,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                ReaderBottomBar(
                    chapters = chapters,
                    currentChapter = currentChapter,
                    lockMenu = lockMenu,
                    bookProgress = bookProgress,
                    bottomBarPadding = bottomBarPadding,
                    backgroundColor = backgroundColor,
                    fontColor = fontColor,
                    scrollToChapter = scrollToChapter,
                    showChaptersDrawer = showChaptersDrawer,
                    showSettingsBottomSheet = showSettingsBottomSheet
                )
            }
        }
    ) {
        ReaderLayout(
            text = text,
            listState = listState,
            volumeKeyPaging = volumeKeyPaging,
            restoreItemIndex = book.scrollIndex,
            bookId = book.id,
            readingMode = readingMode,
            contentPadding = contentPadding,
            verticalPadding = verticalPadding,
            horizontalGesture = horizontalGesture,
            horizontalGestureScroll = horizontalGestureScroll,
            horizontalGestureSensitivity = horizontalGestureSensitivity,
            horizontalGestureAlphaAnim = horizontalGestureAlphaAnim,
            horizontalGesturePullAnim = horizontalGesturePullAnim,
            highlightedReading = highlightedReading,
            highlightedReadingThickness = highlightedReadingThickness,
            progress = progress,
            progressBar = progressBar,
            progressBarPadding = progressBarPadding,
            progressBarAlignment = progressBarAlignment,
            progressBarFontSize = progressBarFontSize,
            paragraphHeight = paragraphHeight,
            sidePadding = sidePadding,
            backgroundColor = backgroundColor,
            fontColor = fontColor,
            images = images,
            imagesCaptions = imagesCaptions,
            imagesCornersRoundness = imagesCornersRoundness,
            imagesAlignment = imagesAlignment,
            imagesWidth = imagesWidth,
            imagesColorEffects = imagesColorEffects,
            fontFamily = fontFamily,
            lineHeight = lineHeight,
            fontThickness = fontThickness,
            fontStyle = fontStyle,
            chapterTitleAlignment = chapterTitleAlignment,
            textAlignment = textAlignment,
            horizontalAlignment = horizontalAlignment,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            paragraphIndentation = paragraphIndentation,
            doubleClickTranslation = doubleClickTranslation,
            isLoading = isLoading,
            showMenu = showMenu,
            menuVisibility = menuVisibility,
            changeProgress = changeProgress,
            updateChapter = updateChapter,
            openShareApp = openShareApp,
            openWebBrowser = openWebBrowser,
            openTranslator = openTranslator,
            openDictionary = openDictionary
        )

        ReaderPerceptionExpander(
            perceptionExpander = perceptionExpander,
            perceptionExpanderPadding = perceptionExpanderPadding,
            perceptionExpanderThickness = perceptionExpanderThickness,
            perceptionExpanderColor = fontColor
        )

        if (isLoading) {
            ReaderLoadingPlaceholder()
        }
    }
}