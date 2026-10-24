package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import java.nio.charset.Charset
import org.junit.jupiter.api.Test

/**
 * Charset detection is worth this many cases because its failures are invisible: GB18030 and
 * UTF-8 both decode each other's Chinese bytes into *something*, so a wrong answer is not an
 * error message but a book of plausible-looking garbage the reader cannot recover from. Every
 * test here therefore asserts the *text*, not just the charset name.
 */
class CharsetDetectorTest {

    private val chinese =
        "夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的。渡口空无一人，" +
            "只有缆绳拍打船身的声音。他站在堤岸上，看着远处灰白色的水面，手心里全是汗。" +
            "傍晚时分，他沿着堤岸往回走，脚下的碎石发出细碎的声响，天边的云被染成了暗红色，像一块正在冷却的铁。"

    private fun encode(text: String, charset: String): ByteArray = text.toByteArray(Charset.forName(charset))

    @Test
    fun `bom-less UTF-16 Chinese is not mistaken for UTF-8`() {
        // Each CJK code unit leaves one byte under 0x80 and one over, and every ASCII character in
        // the text contributes a NUL. That mixture passes a strict UTF-8 decode more often than not
        // — and where it passes, the old answer was "UTF-8" plus a page of control characters.
        for (charset in listOf("UTF-16LE", "UTF-16BE")) {
            val body = (chinese + "\n").repeat(600)
            val bytes = encode(body, charset)
            assertThat(bytes.size).isGreaterThan(64 * 1024)

            val result = CharsetDetector.detect(bytes)
            assertThat(result.charset).isEqualTo(charset)
            assertThat(result.text).isEqualTo(body)
        }
    }

    @Test
    fun `a BOM is a statement and wins over everything else`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encode(chinese, "UTF-8")
        val result = CharsetDetector.detect(utf8)
        assertThat(result.charset).isEqualTo("UTF-8")
        assertThat(result.text).isEqualTo(chinese)

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + encode(chinese, "UTF-16LE")
        val result16 = CharsetDetector.detect(utf16)
        assertThat(result16.charset).isEqualTo("UTF-16LE")
        assertThat(result16.text).isEqualTo(chinese)
    }

    @Test
    fun `a sample boundary that cuts a character does not unmake UTF-8`() {
        // 山 is E5 B1 B1, so 21 845 of them fill 65 535 bytes and the 64 KB sample ends one byte
        // into the next character. The strict probe read that as proof the file was *not* UTF-8 and
        // the trial decode answered GB18030, which decodes anything: every Chinese novel over
        // 64 KB came out as garbage, and a garbage body has no chapter headings to find.
        val text = "山".repeat(21_846 + 20_000)
        val bytes = encode(text, "UTF-8")
        assertThat(bytes.size).isGreaterThan(64 * 1024)

        val result = CharsetDetector.detect(bytes)
        assertThat(result.charset).isEqualTo("UTF-8")
        assertThat(result.confidence).isAtLeast(0.99)
        assertThat(result.text).isEqualTo(text)
    }

    @Test
    fun `strict UTF-8 is proven, not guessed, and ASCII is trivially UTF-8`() {
        val utf8 = CharsetDetector.detect(encode(chinese, "UTF-8"))
        assertThat(utf8.charset).isEqualTo("UTF-8")
        assertThat(utf8.text).isEqualTo(chinese)
        assertThat(utf8.confidence).isAtLeast(0.99)

        val ascii = CharsetDetector.detect(encode("Chapter 1\n\nThe harbour was quiet.", "US-ASCII"))
        assertThat(ascii.charset).isEqualTo("UTF-8")
        assertThat(ascii.text).isEqualTo("Chapter 1\n\nThe harbour was quiet.")
    }

    @Test
    fun `GB18030 bytes are not mistaken for UTF-8 and decode back exactly`() {
        val bytes = encode(chinese, "GB18030")
        // The bytes are invalid UTF-8, so the strict test must reject them - this is the single
        // check that stands between a Chinese novel and a page of mojibake.
        val result = CharsetDetector.detect(bytes)
        assertThat(result.charset).isEqualTo("GB18030")
        assertThat(result.text).isEqualTo(chinese)
        assertThat(result.confidence).isAtLeast(CharsetDetector.REVIEW_CONFIDENCE)
    }

    @Test
    fun `a decode full of replacement or control characters is disqualified rather than ranked`() {
        // 0x80 alone is invalid in UTF-8, unmappable in most candidates, and its decodings contain
        // replacement characters; the answer must still be a usable string, never null or an
        // exception, because this runs while a book is being opened.
        val bytes = ByteArray(2048) { 0x80.toByte() }
        val result = CharsetDetector.detect(bytes)
        assertThat(result.text).isNotNull()
        assertThat(result.charset).isNotEmpty()
        assertThat(result.confidence).isLessThan(CharsetDetector.REVIEW_CONFIDENCE)
    }

    @Test
    fun `an empty file is not a crash`() {
        val result = CharsetDetector.detect(ByteArray(0))
        assertThat(result.charset).isEqualTo("UTF-8")
        assertThat(result.text).isEmpty()
    }
}
