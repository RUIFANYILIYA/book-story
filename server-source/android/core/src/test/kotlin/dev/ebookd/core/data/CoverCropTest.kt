package dev.ebookd.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind "pick a picture and it becomes a cover".
 *
 * The bitmap work around this needs a device, but every way it can go wrong is here: a crop that is
 * not exactly the shelf's ratio leaves a band of the previous cover behind, a crop that is not
 * centred cuts a face in half, and a sampler that guesses high is the difference between a 12 MB
 * decode and an out-of-memory kill on a phone that has been left open too long.
 */
class CoverCropTest {

    private fun assertRatio(
        width: Int,
        height: Int,
    ) {
        // Within a pixel: the rect is integers, and 2:3 does not divide most sizes exactly.
        assertTrue("ratio was ${width.toDouble() / height}", Math.abs(width.toDouble() / height - 2.0 / 3.0) < 0.01)
    }

    @Test
    fun `a landscape photo is cropped down the middle, not off the left edge`() {
        val rect = requireNotNull(CoverCrop.crop(4000, 3000))
        assertEquals(3000, rect.height)
        assertEquals(2000, rect.width)
        assertEquals(1000, rect.x)
        assertEquals(0, rect.y)
        assertRatio(rect.width, rect.height)
    }

    @Test
    fun `a tall portrait loses equal bands top and bottom`() {
        val rect = requireNotNull(CoverCrop.crop(1000, 3000))
        assertEquals(1000, rect.width)
        assertEquals(1500, rect.height)
        assertEquals(0, rect.x)
        assertEquals(750, rect.y)
        assertRatio(rect.width, rect.height)
    }

    @Test
    fun `an image that is already a cover shape is left alone`() {
        val rect = requireNotNull(CoverCrop.crop(800, 1200))
        assertEquals(CoverCrop.SourceRect(0, 0, 800, 1200), rect)
    }

    @Test
    fun `a square gives up its sides rather than its middle`() {
        val rect = requireNotNull(CoverCrop.crop(1000, 1000))
        assertRatio(rect.width, rect.height)
        assertEquals(0, rect.y)
        // A 667-wide crop of a 1000-wide source leaves 333 to divide, so one band is a pixel wider.
        assertTrue(
            "bands ${rect.x} and ${1000 - rect.x - rect.width} are not level",
            Math.abs(rect.x - (1000 - rect.x - rect.width)) <= 1,
        )
    }

    @Test
    fun `the crop never reaches outside the picture, for any shape`() {
        for (w in intArrayOf(1, 7, 99, 400, 1024, 4032)) {
            for (h in intArrayOf(1, 3, 120, 400, 1024, 3024)) {
                val rect = requireNotNull(CoverCrop.crop(w, h)) { "$w×$h had no crop" }
                assertTrue("$w×$h → x+$w outside", rect.x >= 0 && rect.x + rect.width <= w)
                assertTrue("$w×$h → y+$h outside", rect.y >= 0 && rect.y + rect.height <= h)
                assertTrue("$w×$h → empty", rect.width > 0 && rect.height > 0)
            }
        }
    }

    @Test
    fun `a degenerate decode is refused rather than turned into a one-pixel cover`() {
        assertNull(CoverCrop.crop(0, 500))
        assertNull(CoverCrop.crop(500, 0))
        assertNull(CoverCrop.crop(-3, 400))
        assertEquals(0 to 0, CoverCrop.outputSize(0, 0))
    }

    @Test
    fun `the output is capped but never enlarged`() {
        assertEquals(800 to 1200, CoverCrop.outputSize(2000, 3000))
        assertEquals(300 to 450, CoverCrop.outputSize(300, 450))
        // A strip narrower than the cap stays narrow: inventing pixels would only cost bytes.
        val (width, height) = CoverCrop.outputSize(120, 180)
        assertEquals(120, width)
        assertTrue(height <= 180)
    }

    @Test
    fun `the sampler keeps a phone camera decode inside a sane budget`() {
        // 4032×3024 at full size is a 36 MB bitmap. Sampling by two lands on 2016×1512, which is
        // still more than the 1008×1512 the crop needs, so nothing downstream has to invent pixels.
        assertEquals(2, CoverCrop.sampleSize(4032, 3024))
        assertEquals(1, CoverCrop.sampleSize(800, 1200))
        assertEquals(1, CoverCrop.sampleSize(1600, 1600))
        assertEquals(4, CoverCrop.sampleSize(12000, 400))
    }

    @Test
    fun `the sampled decode is still big enough that the final scale does not enlarge`() {
        for (size in listOf(4032 to 3024, 3024 to 4032, 12000 to 400, 400 to 12000)) {
            val sample = CoverCrop.sampleSize(size.first, size.second)
            val decodedW = size.first / sample
            val decodedH = size.second / sample
            val rect = requireNotNull(CoverCrop.crop(decodedW, decodedH))
            val (outW, _) = CoverCrop.outputSize(rect.width, rect.height)
            assertTrue(
                "${size.first}×${size.second} sample=$sample grew $rect.width to $outW",
                outW <= rect.width,
            )
        }
    }
}
