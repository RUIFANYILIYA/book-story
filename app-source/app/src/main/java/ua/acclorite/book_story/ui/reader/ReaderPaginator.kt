/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.yield
import ua.acclorite.book_story.domain.model.reader.ReaderText
import ua.acclorite.book_story.ui.common.components.common.withHighlights

private val ChapterTopSpacing = 22.dp
private val ChapterTitleBottomSpacing = 16.dp
private val ChapterDividerThickness = 1.dp
private val ChapterBottomSpacing = 16.dp
private val SeparatorThickness = 3.dp

/**
 * Everything the pages are built from. Any change here has to produce new pages,
 * so it doubles as the key of the pagination pass.
 */
@Immutable
data class ReaderPaginationConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val sidePaddingPx: Int,
    val paragraphSpacingPx: Float,
    val baseTextStyle: TextStyle,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
    val fontSize: TextUnit,
    val letterSpacing: TextUnit,
    val lineHeight: TextUnit,
    val fontStyle: FontStyle,
    val textAlign: TextAlign,
    val paragraphIndentation: TextUnit,
    val chapterTextStyle: TextStyle,
    val chapterNestedTextStyle: TextStyle,
    val highlightedReading: Boolean,
    val highlightedReadingThickness: FontWeight,
    val images: Boolean,
    val imagesCaptions: Boolean,
    val imagesWidth: Float
)

@Immutable
sealed interface ReaderSliceContent {
    @Immutable
    data class Text(val text: AnnotatedString) : ReaderSliceContent

    @Immutable
    data class Chapter(val title: AnnotatedString, val nested: Boolean) : ReaderSliceContent

    data object Separator : ReaderSliceContent

    @Immutable
    data class Image(val imageBitmap: ImageBitmap) : ReaderSliceContent
}

@Immutable
data class ReaderPageSlice(
    val itemIndex: Int,
    val content: ReaderSliceContent,
    val topSpacingPx: Float,
    val firstLineIndent: TextUnit
)

@Immutable
data class ReaderPage(
    val startItemIndex: Int,
    val slices: List<ReaderPageSlice>
)

/**
 * The exact style a slice is drawn with, so that measured and rendered line
 * breaks can never disagree.
 */
fun paragraphTextStyle(config: ReaderPaginationConfig, firstLineIndent: TextUnit): TextStyle {
    return config.baseTextStyle.merge(
        TextStyle(
            fontFamily = config.fontFamily,
            fontWeight = config.fontWeight,
            fontSize = config.fontSize,
            letterSpacing = config.letterSpacing,
            lineHeight = config.lineHeight,
            fontStyle = config.fontStyle,
            textAlign = config.textAlign,
            textIndent = TextIndent(firstLine = firstLineIndent),
            textDirection = TextDirection.Content,
            lineBreak = LineBreak.Paragraph,
            color = config.baseTextStyle.color
        )
    )
}

fun pageIndexForItem(pages: List<ReaderPage>, itemIndex: Int): Int {
    if (pages.isEmpty()) return 0

    var low = 0
    var high = pages.lastIndex
    var result = 0
    while (low <= high) {
        val middle = (low + high) / 2
        if (pages[middle].startItemIndex <= itemIndex) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result.coerceIn(0, pages.lastIndex)
}

/**
 * A run of entries that is always paginated as a unit: it begins with a chapter
 * heading, which starts a page of its own. Books are measured chapter by chapter
 * so that opening one only costs the chapter being read.
 */
@Immutable
data class ReaderSegment(
    val index: Int,
    val startItemIndex: Int,
    val endItemIndex: Int
)

/**
 * A book without chapter headings would otherwise be measured in one go, so a chapter
 * bigger than this is cut into pieces as well.
 */
private const val MaxSegmentEntries = 400

/**
 * Splits the book on chapter headings. The entries before the first heading (the
 * title page, usually) form their own segment.
 */
fun readerSegments(text: List<ReaderText>): List<ReaderSegment> {
    if (text.isEmpty()) return emptyList()

    val starts = mutableListOf(0)
    text.forEachIndexed { index, entry ->
        if (index > 0 && entry is ReaderText.Chapter) starts.add(index)
    }

    val bounds = mutableListOf(0)
    starts.forEachIndexed { position, start ->
        val end = starts.getOrElse(position + 1) { text.size }
        if (position > 0) bounds.add(start)

        var cursor = start + MaxSegmentEntries
        while (cursor < end) {
            bounds.add(cursor)
            cursor += MaxSegmentEntries
        }
    }
    bounds.add(text.size)

    return bounds.zipWithNext().mapIndexed { position, (start, end) ->
        ReaderSegment(index = position, startItemIndex = start, endItemIndex = end)
    }
}

fun segmentIndexForItem(segments: List<ReaderSegment>, itemIndex: Int): Int {
    var low = 0
    var high = segments.lastIndex
    var result = 0
    while (low <= high) {
        val middle = (low + high) / 2
        if (segments[middle].startItemIndex <= itemIndex) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result.coerceIn(0, segments.lastIndex)
}

suspend fun paginateSegment(
    text: List<ReaderText>,
    segment: ReaderSegment,
    config: ReaderPaginationConfig,
    density: Density,
    measurer: TextMeasurer,
    onProgress: (Float) -> Unit = {}
): List<ReaderPage> = paginateReader(
    text = text,
    config = config,
    density = density,
    measurer = measurer,
    onProgress = onProgress,
    from = segment.startItemIndex,
    until = segment.endItemIndex
)

/**
 * Walks the book once, filling one screen at a time. Paragraphs are cut on line
 * boundaries and every cut is re-measured, so a page can never render taller than
 * the screen it was built for.
 */
suspend fun paginateReader(
    text: List<ReaderText>,
    config: ReaderPaginationConfig,
    density: Density,
    measurer: TextMeasurer,
    onProgress: (Float) -> Unit = {},
    from: Int = 0,
    until: Int = text.size
): List<ReaderPage> {
    val pages = mutableListOf<ReaderPage>()
    if (text.isEmpty() || from >= until) return pages

    val chapterTopPx = with(density) { ChapterTopSpacing.toPx() }
    val chapterTitleBottomPx = with(density) { ChapterTitleBottomSpacing.toPx() }
    val chapterDividerPx = with(density) { ChapterDividerThickness.toPx() }
    val chapterBottomPx = with(density) { ChapterBottomSpacing.toPx() }
    val separatorPx = with(density) { SeparatorThickness.toPx() }
    val fallbackLineHeightPx = if (config.lineHeight.isSp) {
        with(density) { config.lineHeight.toPx() }
    } else {
        with(density) { 16.sp.toPx() }
    }

    val constraints = Constraints(maxWidth = config.pageWidthPx - config.sidePaddingPx * 2)
    val contentWidthPx = constraints.maxWidth.toFloat()
    val contentHeightPx = config.pageHeightPx.toFloat()

    var slices = mutableListOf<ReaderPageSlice>()
    var startItemIndex = -1
    var usedPx = 0f

    fun hasContent() = slices.isNotEmpty()

    fun remaining() = contentHeightPx - usedPx

    fun flush() {
        if (slices.isNotEmpty()) {
            pages.add(ReaderPage(startItemIndex = startItemIndex, slices = slices))
            slices = mutableListOf()
            startItemIndex = -1
            usedPx = 0f
        }
    }

    fun add(
        itemIndex: Int,
        content: ReaderSliceContent,
        spacingPx: Float,
        heightPx: Float,
        indent: TextUnit
    ) {
        if (startItemIndex == -1) startItemIndex = itemIndex
        slices.add(
            ReaderPageSlice(
                itemIndex = itemIndex,
                content = content,
                topSpacingPx = spacingPx,
                firstLineIndent = indent
            )
        )
        usedPx += spacingPx + heightPx
    }

    fun measureText(text: AnnotatedString, style: TextStyle) = measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = Int.MAX_VALUE,
        constraints = constraints
    )

    fun isHidden(index: Int): Boolean {
        val entry = text.getOrNull(index) ?: return false
        val previousEntry = text.getOrNull(index - 1)
        return (!config.images && (entry is ReaderText.Image || previousEntry is ReaderText.Image)) ||
            (!config.imagesCaptions && previousEntry is ReaderText.Image)
    }

    fun textOf(entry: ReaderText.Text): AnnotatedString {
        return if (config.highlightedReading) {
            entry.line.withHighlights(config.highlightedReadingThickness)
        } else entry.line
    }

    /**
     * Height of the first line of the next visible paragraph, so that a chapter
     * heading is never stranded alone at the bottom of a page.
     */
    fun firstLineHeightAfter(index: Int): Float {
        for (next in index + 1 until until) {
            if (isHidden(next)) continue

            val entry = text[next]
            return when (entry) {
                is ReaderText.Text -> {
                    val content = textOf(entry)
                    val layout = measureText(
                        content,
                        paragraphTextStyle(config, config.paragraphIndentation)
                    )
                    if (layout.lineCount <= 0) {
                        fallbackLineHeightPx
                    } else {
                        val line = content.subSequence(
                            layout.getLineStart(0),
                            layout.getLineEnd(0, visibleEnd = false)
                        )
                        measureText(
                            line,
                            paragraphTextStyle(config, config.paragraphIndentation)
                        ).size.height.toFloat()
                    }
                }

                else -> 0f
            }
        }
        return 0f
    }

    for (index in from until until) {
        val entry = text[index]
        // Mirrors the visibility rules of the scrolling layout.
        if (!isHidden(index)) {
            // Whatever opens a page sits flush with its top.
            val spacingPx = if (index > 0 && hasContent()) config.paragraphSpacingPx else 0f

            when (entry) {
                is ReaderText.Text -> {
                    val content = textOf(entry)

                    val paragraphLayout = measureText(
                        content,
                        paragraphTextStyle(config, config.paragraphIndentation)
                    )

                    if (paragraphLayout.lineCount <= 0 || paragraphLayout.size.height <= 0) {
                        if (fallbackLineHeightPx > remaining() - spacingPx && hasContent()) flush()
                        add(
                            itemIndex = index,
                            content = ReaderSliceContent.Text(content),
                            spacingPx = spacingPx,
                            heightPx = fallbackLineHeightPx,
                            indent = config.paragraphIndentation
                        )
                    } else {
                        var startLine = 0
                        var firstChunk = true

                        while (startLine < paragraphLayout.lineCount) {
                            val indent = if (firstChunk) config.paragraphIndentation else 0.sp
                            val style = paragraphTextStyle(config, indent)
                            val blockSpacingPx = if (firstChunk) spacingPx else 0f
                            val available = remaining() - blockSpacingPx

                            // Lines fitting by geometry, then verified by measuring the very cut.
                            val lineTop = paragraphLayout.getLineTop(startLine)
                            var lastLine = startLine
                            while (
                                lastLine + 1 < paragraphLayout.lineCount &&
                                paragraphLayout.getLineBottom(lastLine + 1) - lineTop <= available
                            ) {
                                lastLine++
                            }

                            var slice = content.subSequence(
                                paragraphLayout.getLineStart(startLine),
                                paragraphLayout.getLineEnd(lastLine, visibleEnd = false)
                            )
                            var measured = measureText(slice, style)

                            while (measured.size.height > available && lastLine > startLine) {
                                lastLine--
                                slice = content.subSequence(
                                    paragraphLayout.getLineStart(startLine),
                                    paragraphLayout.getLineEnd(lastLine, visibleEnd = false)
                                )
                                measured = measureText(slice, style)
                            }

                            if (measured.size.height > available && hasContent()) {
                                flush()
                                continue
                            }

                            add(
                                itemIndex = index,
                                content = ReaderSliceContent.Text(slice),
                                spacingPx = blockSpacingPx,
                                heightPx = measured.size.height.toFloat(),
                                indent = indent
                            )
                            startLine = lastLine + 1
                            firstChunk = false
                        }
                    }
                }

                is ReaderText.Chapter -> {
                    val title = AnnotatedString(entry.title).let {
                        if (config.highlightedReading) {
                            it.withHighlights(config.highlightedReadingThickness)
                        } else it
                    }
                    val style = if (!entry.nested) config.chapterTextStyle
                    else config.chapterNestedTextStyle
                    val titleLayout = measureText(title, style)
                    val heightPx = chapterTopPx + titleLayout.size.height + chapterTitleBottomPx +
                        chapterDividerPx + chapterBottomPx

                    // The heading is kept together with the first line of its text,
                    // so it never ends up alone at the bottom of a page.
                    val firstLinePx = firstLineHeightAfter(index)
                        .let { if (it > 0f) it + config.paragraphSpacingPx else 0f }

                    if (spacingPx + heightPx + firstLinePx > remaining() && hasContent()) flush()
                    add(
                        itemIndex = index,
                        content = ReaderSliceContent.Chapter(title, entry.nested),
                        spacingPx = spacingPx,
                        heightPx = heightPx,
                        indent = 0.sp
                    )
                }

                ReaderText.Separator -> {
                    if (separatorPx > remaining() - spacingPx && hasContent()) flush()
                    add(
                        itemIndex = index,
                        content = ReaderSliceContent.Separator,
                        spacingPx = spacingPx,
                        heightPx = separatorPx,
                        indent = 0.sp
                    )
                }

                is ReaderText.Image -> {
                    val bitmap = entry.imageBitmap
                    val widthPx = contentWidthPx * config.imagesWidth
                    val naturalHeightPx = if (bitmap.width > 0) {
                        bitmap.height * (widthPx / bitmap.width)
                    } else 0f
                    val heightPx = naturalHeightPx.coerceAtMost(contentHeightPx)

                    if (heightPx > remaining() - spacingPx && hasContent()) flush()
                    add(
                        itemIndex = index,
                        content = ReaderSliceContent.Image(bitmap),
                        spacingPx = spacingPx,
                        heightPx = heightPx,
                        indent = 0.sp
                    )
                }
            }
        }

        if ((index - from) % 64 == 0) {
            onProgress((index - from) / (until - from).toFloat())
            yield()
        }
    }

    flush()
    onProgress(1f)

    return pages
}
