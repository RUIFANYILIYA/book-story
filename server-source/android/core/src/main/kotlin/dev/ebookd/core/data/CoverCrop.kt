package dev.ebookd.core.data

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turning whatever the reader picked into a cover.
 *
 * The server deliberately checks only a picture's magic bytes — resampling covers would mean a
 * graphics library for the whole product's sake — so the shape of a cover is this app's business.
 * Two decisions live here, and both are arithmetic rather than Android, so they can be read and
 * tested without a device:
 *
 * **The crop is centred.** A cover picked from a photo or a poster is usually composed around its
 * middle; cropping to the top would turn a face into a forehead. The alternative — letterboxing to
 * preserve the whole image — puts grey bars on every cover the reader chose, which is the opposite
 * of why they chose one.
 *
 * **The scale happens before the encode**, because the bytes travel: a 12 MB screenshot would be
 * stored, hashed, refetched on every launch, and decoded at full size to paint a 120 dp rectangle.
 */
object CoverCrop {

    /** Width over height — the shelf card's and the detail page's aspect, which is the one a cover must fit. */
    const val RATIO: Double = 2.0 / 3.0

    /** A cover is drawn about 120 dp wide; twice the widest plausible screen is more than enough. */
    const val MAX_WIDTH = 800

    /** What a decode may be as long as, before the sampler says enough: twice the output width. */
    const val MAX_LONG_EDGE = MAX_WIDTH * 2

    const val JPEG_QUALITY = 85

    data class SourceRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * The largest centred 2:3 rectangle inside `width` × `height`.
     *
     * Returns null for anything degenerate: a zero-sized decode is a failed decode, and inventing a
     * 1×2 crop out of it would produce a cover of noise rather than an error the reader can see.
     */
    fun crop(
        width: Int,
        height: Int,
    ): SourceRect? {
        if (width <= 0 || height <= 0) return null
        return if (width.toDouble() / height > RATIO) {
            val w = min(width, (height * RATIO).roundToInt())
            SourceRect(x = (width - w) / 2, y = 0, width = w, height = height)
        } else {
            val h = min(height, (width / RATIO).roundToInt())
            SourceRect(x = 0, y = (height - h) / 2, width = width, height = h)
        }
    }

    /**
     * The output size, capped at [MAX_WIDTH] and never enlarged.
     *
     * "Never enlarged" is the part that matters: upscaling a 300 px image to 800 does not add detail,
     * it adds bytes to upload and pixels to decode, and the reader sees a blur that cost more to
     * carry.
     */
    fun outputSize(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= MAX_WIDTH) return width to height
        val scale = MAX_WIDTH.toDouble() / width
        return MAX_WIDTH to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * A power-of-two `inSampleSize` that brings the longest edge down to twice what a cover needs.
     *
     * Powers of two are what the decoder actually does — it skips rows and columns — and it is why
     * this happens before the crop at all: decoding a 12 MP photo at full size in order to throw
     * most of it away is the one way this screen can run out of memory. Twice the target rather than
     * exactly it, because the crop discards a third of the picture in the common case, and sampling
     * to the final size first would force the output to be enlarged.
     */
    fun sampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_LONG_EDGE) sample *= 2
        return sample
    }
}
