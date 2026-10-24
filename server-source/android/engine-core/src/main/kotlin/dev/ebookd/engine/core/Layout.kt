package dev.ebookd.engine.core

/**
 * The types the paginator works in: a measured line, a page, and the keys that say when a cached
 * layout is still valid.
 *
 * Nothing here knows about fonts as such — a [GlyphMeasurer] is injected, which is the seam that
 * lets the whole pagination pass run on a plain JVM with a synthetic metric table. That is not a
 * testing convenience bolted on afterwards: it is why the hardest layout rules can be regression
 * tested at all, since `Paint` needs a device and a device needs an emulator.
 *
 * Offsets (`start`/`end`/`firstChar`/`lastChar`) are *character offsets inside one chapter's flat
 * text* — the text of its blocks joined in order. [Chapter.charStart] is what maps that to the
 * document, so an [Anchor] survives a font-size change while a page number does not.
 */

data class FontMetrics(
    /** Distance above the baseline, positive. */
    val ascent: Float,
    /** Distance below the baseline, positive. */
    val descent: Float,
    val leading: Float = 0f,
)

interface GlyphMeasurer {
    fun metrics(): FontMetrics

    /** The advance width of `text[start, end)`, in pixels. */
    fun width(text: CharSequence, start: Int, end: Int): Float

    fun width(text: CharSequence): Float = width(text, 0, text.length)
}

data class PageSpec(
    val pageWidth: Float,
    val pageHeight: Float,
    val fontSize: Float,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    val marginStart: Float = 0f,
    val marginEnd: Float = 0f,
    val lineHeightMultiplier: Float = 1.6f,
    /** Ceiling on generated lines; see [PaginationOverflow]. */
    val maxIterations: Int = 200_000,
) {
    val contentWidth: Float get() = pageWidth - marginStart - marginEnd
    val contentHeight: Float get() = pageHeight - marginTop - marginBottom

    /**
     * The line box is the larger of what the font needs and what the reader asked for: a font with
     * generous metrics at a tight spacing must not have its ascenders clipped, and a small font at
     * loose spacing must not produce cramped-looking text.
     */
    fun lineHeight(measurer: GlyphMeasurer): Float {
        val metrics = measurer.metrics()
        return maxOf(metrics.ascent + metrics.descent + metrics.leading, fontSize * lineHeightMultiplier)
    }
}

enum class LineKind { TEXT, HEADING, IMAGE, RULE, PREFORMATTED }

/**
 * One laid-out line.
 *
 * [justifyGaps] carries the extra space the justification pass added, indexed by `charOffset -
 * start`; it is empty when the line is not justified. Keeping the gaps rather than a final x per
 * character is what lets the renderer draw with a single `drawText` per run and still space CJK
 * correctly.
 */
class PlacedLine(
    val start: Int,
    val end: Int,
    /** Baseline position, measured from the top of the content box. */
    val baseline: Float,
    /** Left offset of the line inside the content box — carries the paragraph indent. */
    val x: Float,
    val kind: LineKind,
    val justifyGaps: FloatArray = EMPTY_GAPS,
) {
    val charCount: Int get() = (end - start).coerceAtLeast(0)

    fun gapAt(offset: Int): Float = if (offset - start in justifyGaps.indices) justifyGaps[offset - start] else 0f

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        val EMPTY_GAPS = FloatArray(0)
    }
}

class Page(
    val lines: List<PlacedLine>,
    val firstChar: Int,
    val lastChar: Int,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/** One chapter laid out: the pages, and the flat text their character offsets index into. */
data class ChapterLayout(
    val text: String,
    val pages: List<Page>,
) {
    /** The whole chapter's laid-out length — the divisor for [Anchor.percent]. */
    val charCount: Int get() = text.length
}

/**
 * A reading position that survives a font-size change, a rotation and a re-pagination: the chapter
 * plus the character offset inside it. A page number cannot do this — it is a property of the
 * current layout, not of the book.
 */
data class Anchor(val chapterIndex: Int, val charOffset: Int) {
    /** Character progress through the chapter, for the "12%" in the UI. */
    fun percent(chapterCharCount: Int): Float =
        if (chapterCharCount <= 0) 0f else (charOffset.toFloat() / chapterCharCount).coerceIn(0f, 1f)
}

/**
 * Everything a cached layout depends on. If any component changes, the cache entry is not reused —
 * which is why the font and theme enter as ids and a size, not as objects.
 */
data class LayoutKey(
    val chapterIndex: Int,
    val docSha: String,
    val contentWidth: Float,
    val contentHeight: Float,
    val fontId: String,
    val sizeSp: Float,
    val lineMult: Float,
    val marginHash: Int,
    val themeHash: Int,
)

/**
 * Thrown instead of hanging when layout cannot make progress.
 *
 * A buggy measurer (a zero-width glyph, a NaN advance) turns the line-filling loop into one that
 * never satisfies its stop condition. Online, that is an ANR and a bug report that says "the app
 * freezes on this book"; this exception turns it into a stack trace that names the spec.
 */
class PaginationOverflow(message: String) : RuntimeException(message)
