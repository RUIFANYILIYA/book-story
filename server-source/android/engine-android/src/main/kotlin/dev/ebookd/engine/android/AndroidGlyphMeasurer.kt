package dev.ebookd.engine.android

import android.graphics.Paint
import android.graphics.Typeface
import dev.ebookd.engine.core.FontMetrics
import dev.ebookd.engine.core.GlyphMeasurer

/**
 * The device-side [GlyphMeasurer]: one `Paint`, one text size, one typeface.
 *
 * The interface carries no size argument because the measurer *is* the size — [PageSpec] asks for
 * metrics and widths and the paginator divides page height by what it gets back. Constructing one
 * per layout instead of mutating a shared paint is what makes that honest: a paint whose size
 * changed mid-layout would produce pages whose line boxes disagree with the text drawn into them,
 * and the disagreement only appears after a font-size change, which is the least tested moment in
 * a reader.
 *
 * Widths come from `measureText` over the requested range, never from a per-character guess times a
 * count: a CJK string's advance is not `n * width(first char)` once punctuation, digits or
 * proportional Latin appear in it, and getting this wrong moves every line break on the page.
 */
class AndroidGlyphMeasurer(
    private val textSizePx: Float,
    typeface: Typeface = Typeface.DEFAULT,
) : GlyphMeasurer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isLinearText = false
        this.textSize = textSizePx
        this.typeface = typeface
    }

    private val cachedMetrics: FontMetrics by lazy {
        val fm = paint.fontMetrics
        FontMetrics(
            ascent = -fm.ascent,
            descent = fm.descent,
            // Negative leading is the font asking for tighter lines than the metrics imply; the
            // paginator adds it, so a negative value would let two lines overlap.
            leading = fm.leading.coerceAtLeast(0f),
        )
    }

    override fun metrics(): FontMetrics = cachedMetrics

    override fun width(text: CharSequence, start: Int, end: Int): Float {
        if (end <= start) return 0f
        // `String` is the case that reaches almost always; CharSequence otherwise cannot be
        // measured directly by Paint.
        val asString = if (text is String) text else text.subSequence(start, end).toString()
        return if (text is String) {
            paint.measureText(asString, start, end)
        } else {
            paint.measureText(asString)
        }
    }
}
