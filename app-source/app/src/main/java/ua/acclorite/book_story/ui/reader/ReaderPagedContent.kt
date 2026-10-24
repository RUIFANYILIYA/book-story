/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as inputKey
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.reader.ReaderText
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.presentation.reader.model.ReaderFontThickness
import ua.acclorite.book_story.presentation.reader.model.ReaderTextAlignment
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import ua.acclorite.book_story.ui.reader.model.FontWithName
import ua.acclorite.book_story.ui.theme.model.HorizontalAlignment
import kotlin.math.roundToInt

/**
 * How many chapters are kept measured at once. Enough to read either way without
 * stopping, small enough that a whole book never sits in memory.
 */
private const val MaxWindowSegments = 4

/**
 * A measured run of chapters plus the settings they were measured for. The settings
 * travel with it so that a page can still be drawn while the new ones are being
 * worked out.
 */
private data class ReaderWindow(
    val sessionKey: String,
    val segments: List<Int>,
    val pages: List<ReaderPage>,
    val config: ReaderPaginationConfig
)

@Composable
fun ReaderPagedContent(
    text: List<ReaderText>,
    listState: LazyListState,
    volumeKeyPaging: Boolean,
    restoreItemIndex: Int,
    bookId: Int,
    fontColor: Color,
    sidePadding: Dp,
    paragraphHeight: Dp,
    paragraphIndentation: TextUnit,
    fontFamily: FontWithName,
    lineHeight: TextUnit,
    fontThickness: ReaderFontThickness,
    fontStyle: FontStyle,
    chapterTitleAlignment: ReaderTextAlignment,
    textAlignment: ReaderTextAlignment,
    horizontalAlignment: Alignment.Horizontal,
    fontSize: TextUnit,
    letterSpacing: TextUnit,
    highlightedReading: Boolean,
    highlightedReadingThickness: FontWeight,
    doubleClickTranslation: Boolean,
    showMenu: Boolean,
    toolbarHidden: Boolean,
    images: Boolean,
    imagesCaptions: Boolean,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesColorEffects: ColorFilter?,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    openTranslator: (ReaderEvent.OnOpenTranslator) -> Unit,
    changeProgress: (ReaderEvent.OnChangeProgress) -> Unit,
    updateChapter: (ReaderEvent.OnUpdateChapter) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fontFamilyResolver = LocalFontFamilyResolver.current
        val layoutDirection = LocalLayoutDirection.current
        val baseTextStyle = LocalTextStyle.current

        val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
        val pageTopPadding = (cutoutPadding.calculateTopPadding() + paragraphHeight)
            .coerceAtLeast(18.dp)
        val pageBottomPadding = (cutoutPadding.calculateBottomPadding() + paragraphHeight)
            .coerceAtLeast(18.dp)

        val pageWidthPx = constraints.maxWidth
        val topPaddingPx = with(density) { pageTopPadding.roundToPx() }
        val bottomPaddingPx = with(density) { pageBottomPadding.roundToPx() }
        val contentHeightPx = (constraints.maxHeight - topPaddingPx - bottomPaddingPx)
            .coerceAtLeast(1)
        val sidePaddingPx = with(density) { sidePadding.roundToPx() }

        val chapterTitleBaseStyle = MaterialTheme.typography.headlineMedium
        val chapterNestedTitleBaseStyle = MaterialTheme.typography.headlineSmall

        val config = remember(
            pageWidthPx,
            contentHeightPx,
            sidePaddingPx,
            paragraphHeight,
            baseTextStyle,
            fontFamily,
            fontSize,
            lineHeight,
            letterSpacing,
            fontThickness,
            fontStyle,
            textAlignment,
            paragraphIndentation,
            chapterTitleAlignment,
            fontColor,
            highlightedReading,
            highlightedReadingThickness,
            images,
            imagesCaptions,
            imagesWidth,
            chapterTitleBaseStyle,
            chapterNestedTitleBaseStyle
        ) {
            val chapterStyle = baseTextStyle.merge(
                chapterTitleBaseStyle.copy(
                    color = fontColor,
                    textAlign = chapterTitleAlignment.textAlignment,
                    textDirection = TextDirection.Content
                )
            )
            val chapterNestedStyle = baseTextStyle.merge(
                chapterNestedTitleBaseStyle.copy(
                    color = fontColor,
                    textAlign = chapterTitleAlignment.textAlignment,
                    textDirection = TextDirection.Content
                )
            )

            ReaderPaginationConfig(
                pageWidthPx = pageWidthPx,
                pageHeightPx = contentHeightPx,
                sidePaddingPx = sidePaddingPx,
                paragraphSpacingPx = with(density) { paragraphHeight.toPx() },
                baseTextStyle = baseTextStyle,
                fontFamily = fontFamily.font,
                fontWeight = fontThickness.thickness,
                fontSize = fontSize,
                letterSpacing = letterSpacing,
                lineHeight = lineHeight,
                fontStyle = fontStyle,
                textAlign = textAlignment.textAlignment,
                paragraphIndentation = paragraphIndentation,
                chapterTextStyle = chapterStyle,
                chapterNestedTextStyle = chapterNestedStyle,
                highlightedReading = highlightedReading,
                highlightedReadingThickness = highlightedReadingThickness,
                images = images,
                imagesCaptions = imagesCaptions,
                imagesWidth = imagesWidth
            )
        }

        val sessionKey = remember(bookId, text.size, config) {
            ReaderPageCache.sessionKey(bookId = bookId, textSize = text.size, config = config)
        }
        val segments = remember(text) { readerSegments(text) }
        val progress = remember(sessionKey) { mutableFloatStateOf(0f) }

        // Only the chapters around the reading position are ever measured, so opening a
        // book or changing a setting costs one chapter instead of the whole book.
        var window by remember { mutableStateOf<ReaderWindow?>(null) }
        var jumpToken by remember(sessionKey) { mutableIntStateOf(0) }
        var jumpItem by remember(sessionKey) { mutableIntStateOf(-1) }

        suspend fun pagesOf(segment: ReaderSegment): List<ReaderPage> {
            val key = ReaderPageCache.key(segment)
            ReaderPageCache.get(key)?.let { return it }

            // Fonts must be settled before line breaks are baked into pages, or the
            // rendered text would break differently from the measured one.
            runCatching { fontFamilyResolver.preload(fontFamily.font) }

            val measured = withContext(Dispatchers.Default) {
                paginateSegment(
                    text = text,
                    segment = segment,
                    config = config,
                    density = density,
                    measurer = TextMeasurer(
                        defaultFontFamilyResolver = fontFamilyResolver,
                        defaultDensity = density,
                        defaultLayoutDirection = layoutDirection,
                        cacheSize = 256
                    ),
                    onProgress = { progress.floatValue = it }
                )
            }
            ReaderPageCache.put(key, measured)
            return measured
        }

        LaunchedEffect(sessionKey, jumpToken) {
            if (text.isEmpty() || segments.isEmpty()) {
                window = null
                return@LaunchedEffect
            }

            ReaderPageCache.startSession(sessionKey)

            val target = if (jumpToken > 0 && jumpItem >= 0) jumpItem
            else listState.firstVisibleItemIndex.takeIf { it > 0 } ?: restoreItemIndex
            val index = segmentIndexForItem(segments, target.coerceIn(0, text.lastIndex))

            window = ReaderWindow(
                sessionKey = sessionKey,
                segments = listOf(index),
                pages = pagesOf(segments[index]),
                config = config
            )
        }

        val currentWindow = window
        when {
            currentWindow == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StyledText(
                        text = stringResource(
                            id = R.string.reader_paginating,
                            (progress.floatValue * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = fontColor.copy(0.6f)
                        ),
                        maxLines = 1
                    )
                }
            }

            currentWindow.pages.isEmpty() -> Unit

            else -> key(currentWindow.pages) {
                val windowPages = currentWindow.pages
                val pageConfig = currentWindow.config
                val fresh = currentWindow.sessionKey == sessionKey
                val windowStartItem = windowPages.first().startItemIndex
                val windowEndItem = segments[currentWindow.segments.last()].endItemIndex

                val startItem = remember(windowPages) {
                    val live = listState.firstVisibleItemIndex
                    if (live > 0 && live < windowEndItem) live else restoreItemIndex
                }
                val pagerState = rememberPagerState(
                    initialPage = pageIndexForItem(windowPages, startItem)
                ) {
                    windowPages.size
                }
                val tapScope = rememberCoroutineScope()

                // The pager owns the reading position, so volume-key paging has to drive the pager
                // rather than the list state the rest of the reader reads from. Key events only
                // reach a node that holds focus, and a page of text holds none, so ask for it.
                val pagerFocusRequester = remember { FocusRequester() }
                LaunchedEffect(volumeKeyPaging) {
                    if (volumeKeyPaging) {
                        withFrameNanos { }
                        runCatching { pagerFocusRequester.requestFocus() }
                    }
                }
                var positionSynced by remember { mutableStateOf(false) }

                fun trimmed(
                    ids: List<Int>,
                    all: List<ReaderPage>
                ): Pair<List<Int>, List<ReaderPage>> {
                    if (ids.size <= MaxWindowSegments) return ids to all
                    val kept = ids.takeLast(MaxWindowSegments)
                    val from = segments[kept.first()].startItemIndex
                    val cut = all.indexOfFirst { it.startItemIndex >= from }
                        .let { if (it < 0) all.size else it }
                    return kept to all.drop(cut)
                }

                // Grows the measured area by one chapter, so a swipe into the next one
                // finds its pages already there.
                suspend fun slide(direction: Int) {
                    val current = window ?: return
                    if (current.sessionKey != sessionKey) return

                    val first = current.segments.first()
                    val last = current.segments.last()
                    val ids: List<Int>
                    val all: List<ReaderPage>

                    if (direction > 0) {
                        if (last >= segments.lastIndex) return
                        ids = current.segments + (last + 1)
                        all = current.pages + pagesOf(segments[last + 1])
                    } else {
                        if (first <= 0) return
                        ids = listOf(first - 1) + current.segments
                        all = pagesOf(segments[first - 1]) + current.pages
                    }

                    val (keptIds, keptPages) = trimmed(ids, all)
                    window = current.copy(segments = keptIds, pages = keptPages)
                }

                // The pager owns the reading position, but the rest of the reader reads
                // it from the list state, so every settled page is written back there.
                LaunchedEffect(pagerState, windowPages) {
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        val current = windowPages.getOrNull(page) ?: return@collect

                        listState.requestScrollToItem(index = current.startItemIndex, scrollOffset = 0)

                        changeProgress(
                            ReaderEvent.OnChangeProgress(
                                progress = if (text.size > 1) {
                                    current.startItemIndex / text.lastIndex.toFloat()
                                } else 0f,
                                firstVisibleItemIndex = current.startItemIndex,
                                firstVisibleItemOffset = 0
                            )
                        )
                        updateChapter(ReaderEvent.OnUpdateChapter(current.startItemIndex))

                        positionSynced = true

                        if (!fresh) return@collect
                        if (page >= windowPages.size - 3) slide(1) else if (page <= 2) slide(-1)
                    }
                }

                // Jumps coming from outside (chapters drawer, progress slider, bookmarks).
                LaunchedEffect(pagerState, windowPages) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { itemIndex ->
                            if (!fresh || !positionSynced) return@collect

                            if (itemIndex in windowStartItem until windowEndItem) {
                                val target = pageIndexForItem(windowPages, itemIndex)
                                if (target != pagerState.currentPage) {
                                    pagerState.scrollToPage(target)
                                }
                            } else {
                                jumpItem = itemIndex
                                jumpToken++
                            }
                        }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(pagerFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (!volumeKeyPaging) return@onPreviewKeyEvent false
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            // Volume down goes forward, the same way round as the tap zones below.
                            when (event.inputKey) {
                                Key.VolumeDown -> {
                                    // targetPage rather than currentPage: while a turn is animating
                                    // the current page lags behind, and a press landing in that
                                    // window would be dropped instead of queued.
                                    val next = pagerState.targetPage + 1
                                    if (next < pagerState.pageCount) {
                                        tapScope.launch { pagerState.animateScrollToPage(next) }
                                    }
                                    true
                                }

                                Key.VolumeUp -> {
                                    val previous = pagerState.targetPage - 1
                                    if (previous >= 0) {
                                        tapScope.launch { pagerState.animateScrollToPage(previous) }
                                    }
                                    true
                                }

                                else -> false
                            }
                        }
                        // The page itself is the gesture surface, split in thirds: a tap on either
                        // margin turns a page the way a swipe would, and the middle opens the menu.
                        // A reader holding the phone one-handed reaches the margins without
                        // aiming, which is the whole point of the zones.
                        .pointerInput(pagerState, showMenu) {
                            detectTapGestures { offset ->
                                val third = size.width / 3f
                                when {
                                    offset.x < third -> {
                                        val previous = pagerState.currentPage - 1
                                        if (previous >= 0) {
                                            tapScope.launch { pagerState.animateScrollToPage(previous) }
                                        }
                                    }

                                    offset.x > third * 2 -> {
                                        val next = pagerState.currentPage + 1
                                        if (next < pagerState.pageCount) {
                                            tapScope.launch { pagerState.animateScrollToPage(next) }
                                        }
                                    }

                                    else -> menuVisibility(
                                        ReaderEvent.OnMenuVisibility(show = !showMenu, saveCheckpoint = true)
                                    )
                                }
                            }
                        },
                    beyondViewportPageCount = 1,
                    key = { it }
                ) { pageIndex ->
                    val page = windowPages.getOrNull(pageIndex) ?: return@HorizontalPager

                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .padding(top = pageTopPadding, bottom = pageBottomPadding)
                    ) {
                        page.slices.forEach { slice ->
                            ReaderPagedSlice(
                                slice = slice,
                                config = pageConfig,
                                fontColor = fontColor,
                                sidePadding = sidePadding,
                                horizontalAlignment = horizontalAlignment,
                                maxImageHeight = with(density) { contentHeightPx.toDp() },
                                imagesCornersRoundness = imagesCornersRoundness,
                                imagesAlignment = imagesAlignment,
                                imagesWidth = imagesWidth,
                                imagesColorEffects = imagesColorEffects,
                                doubleClickTranslation = doubleClickTranslation,
                                showMenu = showMenu,
                                toolbarHidden = toolbarHidden,
                                menuVisibility = menuVisibility,
                                openTranslator = openTranslator
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPagedSlice(
    slice: ReaderPageSlice,
    config: ReaderPaginationConfig,
    fontColor: Color,
    sidePadding: Dp,
    horizontalAlignment: Alignment.Horizontal,
    maxImageHeight: Dp,
    imagesCornersRoundness: Dp,
    imagesAlignment: HorizontalAlignment,
    imagesWidth: Float,
    imagesColorEffects: ColorFilter?,
    doubleClickTranslation: Boolean,
    showMenu: Boolean,
    toolbarHidden: Boolean,
    menuVisibility: (ReaderEvent.OnMenuVisibility) -> Unit,
    openTranslator: (ReaderEvent.OnOpenTranslator) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (slice.topSpacingPx > 0f) {
            Spacer(Modifier.height(with(LocalDensity.current) { slice.topSpacingPx.toDp() }))
        }

        when (val content = slice.content) {
            is ReaderSliceContent.Text -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sidePadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = horizontalAlignment
                ) {
                    StyledText(
                        text = content.text,
                        modifier = Modifier.then(
                            if (doubleClickTranslation && toolbarHidden) {
                                Modifier.noRippleClickable(
                                    onDoubleClick = {
                                        openTranslator(
                                            ReaderEvent.OnOpenTranslator(
                                                textToTranslate = content.text.text,
                                                translateWholeParagraph = true
                                            )
                                        )
                                    },
                                    onClick = {
                                        menuVisibility(
                                            ReaderEvent.OnMenuVisibility(
                                                show = !showMenu,
                                                saveCheckpoint = true
                                            )
                                        )
                                    }
                                )
                            } else Modifier
                        ),
                        style = paragraphTextStyle(config, slice.firstLineIndent),
                        highlightText = false
                    )
                }
            }

            is ReaderSliceContent.Chapter -> {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(22.dp))

                    StyledText(
                        text = content.title,
                        modifier = Modifier
                            .padding(horizontal = sidePadding)
                            .fillMaxWidth(),
                        style = if (!content.nested) config.chapterTextStyle
                        else config.chapterNestedTextStyle,
                        highlightText = false
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = fontColor.copy(0.4f))
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            ReaderSliceContent.Separator -> {
                HorizontalDivider(
                    thickness = 3.dp,
                    modifier = Modifier
                        .padding(horizontal = sidePadding)
                        .clip(CircleShape),
                    color = fontColor.copy(0.3f)
                )
            }

            is ReaderSliceContent.Image -> {
                Box(
                    modifier = Modifier
                        .padding(horizontal = sidePadding)
                        .fillMaxWidth(),
                    contentAlignment = imagesAlignment.alignment
                ) {
                    Image(
                        modifier = Modifier
                            .clip(RoundedCornerShape(imagesCornersRoundness))
                            .fillMaxWidth(imagesWidth)
                            .heightIn(max = maxImageHeight),
                        bitmap = content.imageBitmap,
                        contentDescription = null,
                        colorFilter = imagesColorEffects,
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
