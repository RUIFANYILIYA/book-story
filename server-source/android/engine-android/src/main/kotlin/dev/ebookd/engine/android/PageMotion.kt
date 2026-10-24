package dev.ebookd.engine.android

import android.graphics.Color

/**
 * The reader's colours, in one place, because the renderer owns every pixel it draws.
 *
 * Night / eye-care / parchment / pure-black are palette swaps rather than themes in the Android
 * sense: the document model deliberately carries no colours, because a theme change must not
 * invalidate the parsed book. So switching is assigning a new palette and redrawing — which is also
 * why [warmAlpha] is an overlay alpha rather than a window setting. There is no public API for a
 * hardware blue-light filter, so a control that claimed one would be a lie; this is honestly an
 * in-app warm tint, and the settings sheet says so in those words.
 */
data class ReaderPalette(
    val background: Int,
    val text: Int,
    val headerText: Int,
    /** 0..255 alpha of the warm overlay drawn over the page; 0 disables it. */
    val warmAlpha: Int = 0,
) {
    companion object {
        val Day = ReaderPalette(Color.parseColor("#FBF9F4"), Color.parseColor("#1F2933"), Color.parseColor("#6B7480"))
        val Night = ReaderPalette(Color.parseColor("#12161A"), Color.parseColor("#C9CDD2"), Color.parseColor("#6B7480"))
        val PureBlack = ReaderPalette(Color.BLACK, Color.parseColor("#E8E8E8"), Color.parseColor("#808080"))
        val Sepia = ReaderPalette(Color.parseColor("#EADFC8"), Color.parseColor("#3A2F22"), Color.parseColor("#7A6A52"), warmAlpha = 24)

        val WARM_TINT = Color.parseColor("#FFD9A0")
    }
}

/**
 * A page transition, expressed as horizontal offsets for two pages rather than as a rasterised
 * morph. That covers no-animation, slide and cover, and it costs no extra bitmap per frame.
 *
 * The mesh page curl the plan defers to the second round cannot be expressed this way — it needs
 * the page frozen into bitmaps and warped. The interface stops here on purpose: pretending the seam
 * also accommodates the curl would be a claim about code that does not exist.
 */
interface PageMotion {
    fun onDrag(dxPx: Float, widthPx: Int)

    /** Where the page currently on screen is drawn, in pixels from its resting place. */
    fun currentOffset(): Float

    /** Where the page being revealed is drawn. */
    fun neighbourOffset(): Float

    /** Cover reveals by painting over; slide moves both, so the neighbour goes under. */
    fun neighbourOnTop(): Boolean

    fun reset()
}

class NoneMotion : PageMotion {
    override fun onDrag(dxPx: Float, widthPx: Int) = Unit

    override fun currentOffset(): Float = 0f

    override fun neighbourOffset(): Float = 0f

    override fun neighbourOnTop(): Boolean = false

    override fun reset() = Unit
}

/** Both pages move together: the incoming one pushes the outgoing one out. */
class SlideMotion : PageMotion {
    private var drag = 0f
    private var width = 0

    override fun onDrag(dxPx: Float, widthPx: Int) {
        width = widthPx
        drag = dxPx.coerceIn(-widthPx.toFloat(), widthPx.toFloat())
    }

    override fun currentOffset(): Float = drag

    override fun neighbourOffset(): Float = drag + if (drag < 0f) width.toFloat() else -width.toFloat()

    override fun neighbourOnTop(): Boolean = false

    override fun reset() {
        drag = 0f
    }
}

/** The outgoing page stays put while the incoming one slides over it. */
class CoverMotion : PageMotion {
    private var drag = 0f
    private var width = 0

    override fun onDrag(dxPx: Float, widthPx: Int) {
        width = widthPx
        drag = dxPx.coerceIn(-widthPx.toFloat(), widthPx.toFloat())
    }

    override fun currentOffset(): Float = 0f

    /** Revealing the next page enters from the right; the previous one from the left. */
    override fun neighbourOffset(): Float = if (drag < 0f) width + drag else drag - width

    override fun neighbourOnTop(): Boolean = true

    override fun reset() {
        drag = 0f
    }
}
