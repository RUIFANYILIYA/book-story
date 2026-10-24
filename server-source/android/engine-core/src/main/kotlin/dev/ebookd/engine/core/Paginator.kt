package dev.ebookd.engine.core

import java.text.BreakIterator
import java.util.Locale

/**
 * Lays a chapter's blocks out into pages.
 *
 * The pass is pure: `(chapter, spec, measurer) -> pages`. That is what makes a page count stable
 * enough to regression test, and it is why a settings change re-runs this over *one* chapter
 * instead of re-parsing the book — the document model already holds the text, only the geometry
 * changed.
 *
 * Three rules in here are load-bearing and easy to get wrong in the same direction:
 *
 *  - 禁则 (kinsoku): a line may not begin with `，。、` and may not end with `（《“`. Without the
 *    filter, punctuation lands at the start of a line, which in Chinese typography reads as a
 *    typo, not as a style choice.
 *  - Justification spends its extra space on ASCII spaces first and only then on the gaps between
 *    CJK characters, each capped at 0.33em. Uncapped CJK gaps are how a justified Chinese page
 *    ends up with rivers of white between two characters.
 *  - Widow and orphan control: a paragraph never shows a single line at the bottom or the top of a
 *    page, and a heading never sits alone at the foot of a page.
 */
object Paginator {

    /** Characters that may not start a line. */
    private const val NO_LINE_START = "，。、；：！？）］｝〉》」』】”’…—·%‰℃°.,!?)]}\"'"

    /** Characters that may not end a line. */
    private const val NO_LINE_END = "（［｛〈《「『【“‘([{\"'"

    private const val ZERO_WIDTH_JOINER = '\u200D'

    /** Each justification gap between CJK characters is capped at this fraction of the font size. */
    private const val MAX_JUSTIFY_GAP_EM = 0.33f

    private const val DEFAULT_IMAGE_HEIGHT_EM = 12f
    private const val EPSILON = 0.01f

    /**
     * The chapter as pages, plus the single string every offset in those pages indexes into.
     *
     * The text travels with the layout because it cannot be rebuilt faithfully by the renderer:
     * nested blocks are flattened in a specific order and with no separators, and a renderer that
     * guessed the concatenation would draw the right *shape* of page with the wrong characters in
     * it.
     */
    fun paginate(chapter: Chapter, spec: PageSpec, measurer: GlyphMeasurer): ChapterLayout {
        val flat = FlatText()
        val items = flatten(chapter.blocks, flat)
        if (items.isEmpty()) return ChapterLayout("", listOf(Page(emptyList(), 0, 0)))

        val text = flat.text
        val prefix = widthPrefix(text, measurer)
        val boundaries = lineBoundaries(text)
        return ChapterLayout(text, place(breakItems(items, text, prefix, boundaries, spec), spec, measurer))
    }

    // ------------------------------------------------------------------ anchor math

    /** The page that contains a character offset. Pages ascend by `firstChar`, so: binary search. */
    fun pageIndexFor(pages: List<Page>, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        var low = 0
        var high = pages.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (pages[middle].firstChar <= charOffset) low = middle else high = middle - 1
        }
        return low
    }

    fun locate(pages: List<Page>, anchor: Anchor): Int = pageIndexFor(pages, anchor.charOffset)

    /** The anchor a page resolves to: the chapter plus the offset of its first character. */
    fun anchorOf(pages: List<Page>, chapterIndex: Int, pagePosition: Int): Anchor {
        val page = pages.getOrNull(pagePosition) ?: return Anchor(chapterIndex, 0)
        return Anchor(chapterIndex, page.firstChar)
    }

    // ------------------------------------------------------------------ flattening

    private sealed interface Item

    private class TextItem(
        val start: Int,
        val end: Int,
        val kind: LineKind,
        val align: BlockAlign,
        val indentEm: Float,
        val keepWithNext: Boolean,
        val spaceBeforeEm: Float,
        val spaceAfterEm: Float,
        val preformatted: Boolean,
    ) : Item

    /** An image or a rule: atomic, never split, moved to the next page when it does not fit. */
    private class AtomicItem(
        val offset: Int,
        val kind: LineKind,
        val heightEm: Float,
        val spaceBeforeEm: Float,
        val spaceAfterEm: Float,
    ) : Item

    private object ForcedPageBreak : Item

    private class FlatText {
        val builder = StringBuilder()
        val text: String get() = builder.toString()

        fun append(chars: CharSequence): Pair<Int, Int> {
            val start = builder.length
            builder.append(chars)
            return start to builder.length
        }
    }

    private fun flatten(blocks: List<Block>, flat: FlatText): List<Item> {
        val items = mutableListOf<Item>()
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val (start, end) = flat.append(block.spans.joinToString("") { it.text })
                    items += TextItem(
                        start, end, LineKind.TEXT, block.align, block.firstLineIndentEm,
                        block.keepWithNext, block.marginBeforeEm, block.marginAfterEm, preformatted = false,
                    )
                }
                is Block.Heading -> {
                    val (start, end) = flat.append(block.spans.joinToString("") { it.text })
                    items += TextItem(
                        start, end, LineKind.HEADING,
                        if (block.centered) BlockAlign.CENTER else BlockAlign.START,
                        indentEm = 0f, keepWithNext = true, spaceBeforeEm = 0.6f, spaceAfterEm = 0.4f,
                        preformatted = false,
                    )
                }
                is Block.Preformatted -> {
                    val (start, end) = flat.append(block.text)
                    items += TextItem(
                        start, end, LineKind.PREFORMATTED, BlockAlign.START,
                        indentEm = 0f, keepWithNext = false, spaceBeforeEm = 0f, spaceAfterEm = 0.6f,
                        preformatted = true,
                    )
                }
                is Block.ImageBlock -> items += AtomicItem(
                    offset = flat.builder.length,
                    kind = LineKind.IMAGE,
                    heightEm = block.heightEm ?: DEFAULT_IMAGE_HEIGHT_EM,
                    spaceBeforeEm = 0.6f,
                    spaceAfterEm = 0.6f,
                )
                Block.HorizontalRule -> items += AtomicItem(
                    offset = flat.builder.length,
                    kind = LineKind.RULE,
                    heightEm = 0f,
                    spaceBeforeEm = 0.6f,
                    spaceAfterEm = 0.6f,
                )
                Block.PageBreak -> items += ForcedPageBreak
                is Block.Quote -> items += flatten(block.blocks, flat)
                is Block.BulletList -> block.items.forEach { items += flatten(it, flat) }
                is Block.FootnoteRef -> {
                    val (start, end) = flat.append(block.label)
                    items += TextItem(
                        start, end, LineKind.TEXT, BlockAlign.START, indentEm = 0f, keepWithNext = false,
                        spaceBeforeEm = 0f, spaceAfterEm = 0.4f, preformatted = false,
                    )
                }
            }
        }
        return items
    }

    // ------------------------------------------------------------------ measurement

    /**
     * Cumulative width per character, measured once.
     *
     * Measuring runs on demand would be quadratic in the paragraph length, and on Android each call
     * is a `Paint` round trip; one prefix sum turns every later width question into a subtraction.
     */
    private fun widthPrefix(text: String, measurer: GlyphMeasurer): FloatArray {
        val prefix = FloatArray(text.length + 1)
        for (index in text.indices) {
            prefix[index + 1] = prefix[index] + measurer.width(text, index, index + 1)
        }
        return prefix
    }

    /** Every break opportunity the line iterator offers, in one walk of the text. */
    private fun lineBoundaries(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val iterator = BreakIterator.getLineInstance(Locale.ROOT)
        iterator.setText(text)
        val positions = mutableListOf<Int>()
        var position = iterator.first()
        while (position != BreakIterator.DONE) {
            if (position in 1 until text.length) positions += position
            position = iterator.next()
        }
        return positions.toIntArray()
    }

    // ------------------------------------------------------------------ line breaking

    private sealed interface Placeable

    private class BrokenLine(
        val start: Int,
        val end: Int,
        val kind: LineKind,
        val align: BlockAlign,
        val indentEm: Float,
        val lineInBlock: Int,
        val linesInBlock: Int,
        val keepWithNext: Boolean,
        val spaceBeforeEm: Float,
        val spaceAfterEm: Float,
        val gaps: FloatArray,
    ) : Placeable {
        val firstOfBlock: Boolean get() = lineInBlock == 0
        val lastOfBlock: Boolean get() = lineInBlock == linesInBlock - 1
    }

    private class AtomicLine(
        val offset: Int,
        val kind: LineKind,
        val heightEm: Float,
        val spaceBeforeEm: Float,
        val spaceAfterEm: Float,
    ) : Placeable

    private object BreakMarker : Placeable

    private fun breakItems(
        items: List<Item>,
        text: String,
        prefix: FloatArray,
        boundaries: IntArray,
        spec: PageSpec,
    ): List<Placeable> {
        val out = mutableListOf<Placeable>()
        var produced = 0

        /** The circuit breaker: layout that keeps producing lines is a buggier measurer, not a longer book. */
        fun guard(lines: Int) {
            produced += lines
            if (produced > spec.maxIterations) {
                throw PaginationOverflow(
                    "layout produced more than ${spec.maxIterations} lines for a ${text.length}-character chapter " +
                        "(content ${spec.contentWidth}x${spec.contentHeight}, font ${spec.fontSize}); " +
                        "the measurer or the spec is degenerate",
                )
            }
        }

        for (item in items) {
            when (item) {
                ForcedPageBreak -> out += BreakMarker
                is AtomicItem -> {
                    guard(1)
                    out += AtomicLine(item.offset, item.kind, item.heightEm, item.spaceBeforeEm, item.spaceAfterEm)
                }
                is TextItem -> {
                    if (item.start >= item.end) continue
                    val ranges = if (item.preformatted) {
                        splitPreformatted(item, text)
                    } else {
                        wrap(item, text, prefix, boundaries, spec)
                    }
                    guard(ranges.size)
                    ranges.forEachIndexed { index, range ->
                        val last = index == ranges.size - 1
                        out += BrokenLine(
                            start = range.first,
                            end = range.last + 1,
                            kind = item.kind,
                            align = item.align,
                            indentEm = if (index == 0) item.indentEm else 0f,
                            lineInBlock = index,
                            linesInBlock = ranges.size,
                            keepWithNext = item.keepWithNext,
                            spaceBeforeEm = if (index == 0) item.spaceBeforeEm else 0f,
                            spaceAfterEm = if (last) item.spaceAfterEm else 0f,
                            gaps = justifyGaps(item, range, text, prefix, spec, last),
                        )
                    }
                }
            }
        }
        return out
    }

    /** Preformatted text: every source line is a line, and it is never re-wrapped. */
    private fun splitPreformatted(item: TextItem, text: String): List<IntRange> {
        val lines = mutableListOf<IntRange>()
        var start = item.start
        for (index in item.start until item.end) {
            if (text[index] == '\n') {
                lines += start until index
                start = index + 1
            }
        }
        lines += start until item.end
        return lines
    }

    /**
     * Greedy fill with the 禁则 filter applied to the candidates the line iterator offers.
     *
     * `BreakIterator` supplies the *legal* break opportunities of the script — it already knows
     * that ASCII words are not split and that CJK text may break between any two characters — and
     * the filter removes the ones that would put a forbidden character at the start of the next
     * line. When no legal candidate fits, the last illegal one is used rather than overflowing:
     * a misplaced comma is a blemish, a line running off the page is a bug.
     */
    private fun wrap(
        item: TextItem,
        text: String,
        prefix: FloatArray,
        boundaries: IntArray,
        spec: PageSpec,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var lineStart = item.start
        var iterations = 0

        while (lineStart < item.end) {
            iterations += 1
            // Each pass consumes at least one character, so this cannot spin on its own; it is a
            // backstop for a measurer that returns NaN or negative widths, which would otherwise
            // make `furthestFitting` lie.
            if (iterations > MAX_WRAP_ITERATIONS_PER_BLOCK) {
                throw PaginationOverflow("line breaking did not advance inside [$lineStart, ${item.end})")
            }

            val indent = if (out.isEmpty()) item.indentEm * spec.fontSize else 0f
            val available = (spec.contentWidth - indent).coerceAtLeast(1f)
            val furthest = furthestFitting(prefix, lineStart, item.end, available)

            var legal = -1
            var any = -1
            if (item.end <= furthest) {
                // The rest of the block fits inside the measure: take all of it. This is also the
                // only way a final line can be full, because the end-of-text boundary is not in
                // `boundaries` — and without it every block would end one character early, which
                // 禁则 then turns into a one-character orphan line.
                legal = item.end
            } else {
                var index = lowerBound(boundaries, lineStart + 1)
                while (index < boundaries.size && boundaries[index] <= furthest) {
                    val candidate = boundaries[index]
                    if (breakAllowed(text, candidate)) {
                        legal = candidate
                        any = candidate
                    } else {
                        any = candidate
                    }
                    index += 1
                }
            }

            val lineEnd = when {
                legal > lineStart -> legal
                any > lineStart -> any
                else -> (lineStart + 1).coerceAtMost(item.end)
            }
            val contentEnd = maxOf(trimTrailingSpaces(text, lineStart, lineEnd), lineStart + 1).coerceAtMost(item.end)
            out += lineStart until contentEnd
            lineStart = lineEnd
            // Spaces at the start of the next line were consumed by the break itself.
            while (lineStart < item.end && text[lineStart] == ' ') lineStart += 1
        }
        if (out.isEmpty()) out += item.start until item.start
        return out
    }

    private const val MAX_WRAP_ITERATIONS_PER_BLOCK = 100_000

    /** The last offset whose width still fits, never before `start + 1`. */
    private fun furthestFitting(prefix: FloatArray, start: Int, end: Int, available: Float): Int {
        var low = start + 1
        var high = end
        var best = low
        while (low <= high) {
            val middle = (low + high) / 2
            if (prefix[middle] - prefix[start] <= available) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun lowerBound(values: IntArray, target: Int): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) / 2
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * 禁则, plus the two cases a line iterator does not cover on its own: a Latin word is never
     * split in half, and an emoji sequence is never split at its joiner.
     */
    private fun breakAllowed(text: String, position: Int): Boolean {
        if (position <= 0 || position >= text.length) return true
        val previous = text[position - 1]
        val next = text[position]
        if (next in NO_LINE_START) return false
        if (previous in NO_LINE_END) return false
        if (next == ZERO_WIDTH_JOINER || previous == ZERO_WIDTH_JOINER) return false
        if (isVariationSelector(next)) return false
        if (isLatinWordChar(previous) && isLatinWordChar(next)) return false
        return true
    }

    private fun isLatinWordChar(char: Char): Boolean =
        char.code < 0x80 && (char.isLetterOrDigit() || char == '\'')

    private fun isVariationSelector(char: Char): Boolean =
        char.code in 0xFE00..0xFE0F || char.code in 0x1F3FB..0x1F3FF

    private fun trimTrailingSpaces(text: String, start: Int, end: Int): Int {
        var trimmed = end
        while (trimmed > start && text[trimmed - 1] == ' ') trimmed -= 1
        return trimmed
    }

    /**
     * Extra space per gap, or an empty array when the line needs none.
     *
     * The last line of a block is never justified — stretching `全文完` across the measure is the
     * classic mis-application of the rule — and a line whose gaps cannot absorb the surplus at the
     * 0.33em cap is left under-filled rather than spaced out.
     */
    private fun justifyGaps(
        item: TextItem,
        range: IntRange,
        text: String,
        prefix: FloatArray,
        spec: PageSpec,
        isLastLine: Boolean,
    ): FloatArray {
        if (item.align != BlockAlign.JUSTIFY || isLastLine || spec.contentWidth <= 0f) return PlacedLine.EMPTY_GAPS
        val indent = if (range.first == item.start) item.indentEm * spec.fontSize else 0f
        val natural = prefix[range.last + 1] - prefix[range.first]
        val extra = spec.contentWidth - indent - natural
        if (extra <= 0.01f) return PlacedLine.EMPTY_GAPS

        val gaps = FloatArray(range.last + 1 - range.first)
        val spaces = mutableListOf<Int>()
        val cjk = mutableListOf<Int>()
        for (offset in range.first until range.last) {
            val index = offset - range.first
            val left = text[offset]
            val right = text[offset + 1]
            when {
                left == ' ' || right == ' ' -> spaces += index
                !isLatinWordChar(left) || !isLatinWordChar(right) -> cjk += index
            }
        }
        if (spaces.isNotEmpty()) {
            val share = extra / spaces.size
            for (index in spaces) gaps[index] = share
        } else if (cjk.isNotEmpty()) {
            val share = minOf(extra / cjk.size, MAX_JUSTIFY_GAP_EM * spec.fontSize)
            for (index in cjk) gaps[index] = share
        }
        return gaps
    }

    // ------------------------------------------------------------------ placement

    private fun place(placeables: List<Placeable>, spec: PageSpec, measurer: GlyphMeasurer): List<Page> {
        val lineHeight = spec.lineHeight(measurer)
        val ascent = measurer.metrics().ascent
        val pages = mutableListOf<MutableList<PlacedLine>>()
        var current = mutableListOf<PlacedLine>()
        var y = 0f

        fun commit() {
            if (current.isNotEmpty()) pages += current
            current = mutableListOf()
            y = 0f
        }

        for ((index, placeable) in placeables.withIndex()) {
            when (placeable) {
                BreakMarker -> commit()
                is AtomicLine -> {
                    val height = placeable.heightEm * spec.fontSize
                    val spaceBefore = if (current.isEmpty()) 0f else placeable.spaceBeforeEm * spec.fontSize
                    if (current.isNotEmpty() && y + spaceBefore + height > spec.contentHeight + EPSILON) commit()
                    val top = if (current.isEmpty()) 0f else y + placeable.spaceBeforeEm * spec.fontSize
                    // An image taller than the page still gets a page of its own rather than a
                    // vanished one; the renderer clips it, and a clipped image beats no image.
                    current += PlacedLine(placeable.offset, placeable.offset, top + height, 0f, placeable.kind)
                    y = top + height + placeable.spaceAfterEm * spec.fontSize
                }
                is BrokenLine -> {
                    var spaceBefore = if (current.isEmpty()) 0f else placeable.spaceBeforeEm * spec.fontSize
                    val nextHeight = nextHeight(placeables, index, lineHeight, spec)
                    val fits = y + spaceBefore + lineHeight <= spec.contentHeight + EPSILON
                    // A paragraph's first line alone at the foot of a page is an orphan; a heading
                    // whose following line lands on the next page is stranded.
                    val orphan = placeable.firstOfBlock && placeable.linesInBlock >= 2 && current.isNotEmpty() &&
                        y + spaceBefore + 2 * lineHeight > spec.contentHeight + EPSILON
                    val stranded = placeable.keepWithNext && current.isNotEmpty() &&
                        y + spaceBefore + lineHeight + nextHeight > spec.contentHeight + EPSILON

                    if (current.isNotEmpty() && (!fits || orphan || stranded)) {
                        // Widow control: the new page must not start with the paragraph's last line
                        // alone, so the line before it comes along.
                        val widow = placeable.lastOfBlock && !placeable.firstOfBlock && placeable.linesInBlock >= 2
                        if (widow && current.size >= 2) {
                            val carry = current.removeAt(current.size - 1)
                            commit()
                            current += PlacedLine(carry.start, carry.end, ascent, carry.x, carry.kind, carry.justifyGaps)
                            y = lineHeight
                        } else {
                            commit()
                        }
                        spaceBefore = if (current.isEmpty()) 0f else placeable.spaceBeforeEm * spec.fontSize
                    }
                    val top = y + spaceBefore
                    current += PlacedLine(
                        start = placeable.start,
                        end = placeable.end,
                        baseline = top + ascent,
                        x = placeable.indentEm * spec.fontSize,
                        kind = placeable.kind,
                        justifyGaps = placeable.gaps,
                    )
                    y = top + lineHeight
                }
            }
        }
        commit()
        return pages.map { Page(it, it.first().start, it.last().end) }
    }

    private fun nextHeight(placeables: List<Placeable>, index: Int, lineHeight: Float, spec: PageSpec): Float =
        when (val next = placeables.getOrNull(index + 1)) {
            is AtomicLine -> next.heightEm * spec.fontSize
            is BrokenLine -> lineHeight
            else -> 0f
        }
}
