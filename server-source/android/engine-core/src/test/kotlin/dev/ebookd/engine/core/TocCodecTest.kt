package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * What the codec refuses, and why refusal is the only safe answer.
 *
 * A misread chapter list is worse than no list: the offsets still land inside the text, so a saved
 * reading position resolves to a paragraph that is merely wrong. Every `isNull` below is a case
 * where the caller is meant to throw the cache away and re-split, not to make the best of it.
 */
class TocCodecTest {

    private val sourceChars = 4_000

    private val chapters = listOf(
        Chapter(index = 0, title = "第一章 风雪夜", level = 1, charStart = 0, charEnd = 1_200),
        Chapter(index = 1, title = "第二章 渡口", level = 1, charStart = 1_200, charEnd = 3_100),
        Chapter(index = 2, title = "番外 · 灯塔", level = 2, charStart = 3_100, charEnd = 4_000),
    )

    private fun encode(list: List<Chapter> = chapters, chars: Int = sourceChars) =
        TocCodec.encode(list, chars)

    private fun decode(encoded: String, chars: Int = sourceChars) = TocCodec.decode(encoded, chars)

    @Test
    fun `a table of contents survives a round trip with its anchors intact`() {
        val decoded = decode(encode())
        assertThat(decoded).isEqualTo(chapters)
        // Blocks are rebuilt from the normalized text on open; the codec carries only what cannot
        // be derived, so a decoded chapter has none.
        assertThat(decoded!!.all { it.blocks.isEmpty() }).isTrue()
    }

    @Test
    fun `titles containing the field separator or a backslash are escaped, not mangled`() {
        val nasty = listOf(
            Chapter(index = 0, title = "第一章\t带制表符", level = 1, charStart = 0, charEnd = 10),
            Chapter(index = 1, title = "第二章\\反斜杠", level = 1, charStart = 10, charEnd = 20),
            Chapter(index = 2, title = "第三章\n换行", level = 1, charStart = 20, charEnd = 30),
        )
        val encoded = encode(nasty, 30)
        // One encoded line per chapter: a raw separator in a title would silently become a new
        // field and shift every offset on decode.
        assertThat(encoded.lines().count { it.startsWith("#") || it.firstOrNull()?.isDigit() == true })
            .isEqualTo(4)
        assertThat(decode(encoded, 30)).isEqualTo(nasty)
    }

    @Test
    fun `an unknown version is refused rather than guessed at`() {
        assertThat(decode(encode().replaceFirst("ebookd-toc/2", "ebookd-toc/3"))).isNull()
        assertThat(decode("")).isNull()
        assertThat(decode("not a toc at all")).isNull()
    }

    @Test
    fun `a list written for a different document is refused`() {
        // The trailer records how long the normalized text was. Two bytes that normalize to the
        // same length but different chapters are the case to fear, and a length check alone is not
        // the whole defence — it is the cheap one that catches the common accidental mismatch.
        assertThat(decode(encode(), sourceChars + 1)).isNull()
        assertThat(decode(encode(), sourceChars - 1)).isNull()
    }

    @Test
    fun `a truncated file is refused, which is why the length is a trailer and not a header`() {
        val encoded = encode()
        // Dropping the last line is the corruption this format exists to survive: what remains
        // parses, ascends and describes a book missing its ending.
        val lines = encoded.lines()
        assertThat(decode(lines.dropLast(2).joinToString("\n") + "\n")).isNull()
        // Cutting mid-line loses the trailer too.
        assertThat(decode(encoded.dropLast(3))).isNull()
    }

    @Test
    fun `a mutated line decodes to null so the caller re-splits instead of trusting it`() {
        val encoded = encode()
        assertThat(decode(encoded.replace("第一章 风雪夜", "第一章"))).isNotNull()
        assertThat(decode(encoded.replace("\t1\t", "\tx\t"))).isNull()
        assertThat(decode(encoded + "garbage without fields\n")).isNull()
        assertThat(decode(encoded.replace("1200", "-1"))).isNull()
        // Offsets that do not ascend are not a table of contents: the reader's saved position is
        // resolved against them, and a stale cache must be discarded, not sorted into shape.
        val outOfOrder = "ebookd-toc/2\n0\t1\t500\t900\t第一章\n1\t1\t100\t400\t第二章\n#$sourceChars\n"
        assertThat(decode(outOfOrder)).isNull()
    }

    @Test
    fun `an empty chapter list round trips as an empty list`() {
        assertThat(decode(encode(emptyList(), 0), 0)).isEmpty()
    }

    @Test
    fun `a stored list answers one row at a time without reading the others`() {
        val stored = TocCodec.inspect(encode(), sourceChars)!!
        assertThat(stored.lineCount).isEqualTo(chapters.size)
        for (chapter in chapters) {
            // Addressed by position, which the codec also checks the row against: the third row
            // saying it is the second is not something a reader may resolve an anchor with.
            assertThat(stored.chapter(chapter.index)).isEqualTo(chapter)
        }
        assertThat(stored.chapter(chapters.size)).isNull()
        assertThat(stored.chapter(-1)).isNull()
        assertThat(stored.rows()).isEqualTo(chapters)
    }

    @Test
    fun `inspecting refuses a file whose ends are not what encode wrote`() {
        val encoded = encode()
        assertThat(TocCodec.inspect(encoded, sourceChars)).isNotNull()
        // The rows are not parsed by `inspect`, so a mangled one is not its business — but the
        // header, the trailing newline and the trailer are, because those are what a partial write
        // destroys.
        assertThat(TocCodec.inspect(encoded.replace("第一章 风雪夜", "x"), sourceChars)).isNotNull()
        assertThat(TocCodec.inspect(encoded.replace("\t1\t", "\tx\t"), sourceChars)).isNotNull()
        assertThat(TocCodec.inspect(encoded.dropLast(1), sourceChars)).isNull()
        assertThat(TocCodec.inspect(encoded.dropLast(3), sourceChars)).isNull()
        assertThat(TocCodec.inspect(encoded, sourceChars + 1)).isNull()
        assertThat(TocCodec.inspect("", sourceChars)).isNull()
        assertThat(TocCodec.inspect("not a toc at all", sourceChars)).isNull()
    }

    @Test
    fun `a row that is not one is refused by the row reader, not by the inspector`() {
        val stored = TocCodec.inspect(encode(), sourceChars)!!
        // The level field turned into a word: `inspect` passes it, the row reader does not, and a
        // reader that meets it rebuilds the cache.
        val mangled = TocCodec.inspect(encode().replace("\t1\t", "\tx\t"), sourceChars)!!
        assertThat(mangled.chapter(0)).isNull()
        assertThat(mangled.rows()).isNull()
        // Position still addresses a row: the count came from the file's newlines and does not
        // depend on any of them being readable.
        assertThat(mangled.lineCount).isEqualTo(stored.lineCount)
    }
}
