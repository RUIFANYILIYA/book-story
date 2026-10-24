package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The document builder is where a split becomes something pageable, so the invariant that matters
 * most is coverage: the chapter slices have to tile the normalized text exactly. A gap would
 * silently delete a paragraph from the book, and no screen would ever show that it happened.
 *
 * Every chapter body below is deliberately longer than `minChapterChars`, so these tests exercise
 * the builder rather than the short-chapter merge rule.
 */
class TxtDocumentBuilderTest {

    private val size = 10f

    private class FakeMeasurer(private val fontSize: Float) : GlyphMeasurer {
        override fun metrics(): FontMetrics = FontMetrics(ascent = fontSize * 0.8f, descent = fontSize * 0.2f)

        override fun width(text: CharSequence, start: Int, end: Int): Float {
            var total = 0f
            for (index in start until end) {
                total += if (text[index].code >= 0x2E80) fontSize else fontSize * 0.5f
            }
            return total
        }
    }

    private fun doc(text: String): BookDoc =
        TxtDocumentBuilder.build(text, TxtChapterSplitter.split(text), "测试书")

    private val body = "夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的，渡口空无一人。"

    private val threeChapters = buildString {
        appendLine("第一章 起风")
        appendLine()
        appendLine("　　$body")
        appendLine()
        appendLine("第二章 落雨")
        appendLine()
        appendLine("第二段没有缩进，用来区分作者的两种排版习惯。$body")
        appendLine()
        appendLine("第三章 归途")
        appendLine()
        appendLine("　　天边的云被染成暗红色，像一块正在冷却的铁，故事到这里收束。$body")
    }

    @Test
    fun `chapter slices tile the document with no gap and no overlap`() {
        val document = doc(threeChapters)

        assertThat(document.chapters).hasSize(3)
        assertThat(document.chapters.first().charStart).isEqualTo(0)
        assertThat(document.chapters.last().charEnd).isEqualTo(threeChapters.length)
        for (index in 1 until document.chapters.size) {
            assertThat(document.chapters[index].charStart).isEqualTo(document.chapters[index - 1].charEnd)
        }
        // The coverage invariant stated directly: re-joining the slices reproduces the book.
        assertThat(
            document.chapters.joinToString("") { threeChapters.substring(it.charStart, it.charEnd) },
        ).isEqualTo(threeChapters)
    }

    @Test
    fun `a real heading line becomes a heading block and a generated one does not`() {
        val document = doc(threeChapters)
        assertThat(document.chapters.first().blocks.first()).isInstanceOf(Block.Heading::class.java)

        // A length-fallback chapter is named "全文" or "第 N 部分" — text that exists nowhere in the
        // book. Inventing a heading line for it would put a sentence on the page the author never
        // wrote.
        val headingless = doc("这是一段没有任何标题的长正文，反复出现以构成一篇没有标题的书。".repeat(40))
        assertThat(headingless.chapters.first().blocks.first()).isNotInstanceOf(Block.Heading::class.java)
    }

    @Test
    fun `the indent marker becomes paragraph indentation and never reaches the text`() {
        val document = doc(threeChapters)
        val indented = document.chapters[0].blocks.filterIsInstance<Block.Paragraph>().single()
        val flush = document.chapters[1].blocks.filterIsInstance<Block.Paragraph>().single()

        assertThat(indented.firstLineIndentEm).isEqualTo(2f)
        assertThat(flush.firstLineIndentEm).isEqualTo(0f)
        assertThat(
            document.chapters
                .flatMap { it.blocks }
                .filterIsInstance<Block.Paragraph>()
                .none { paragraph -> paragraph.spans.any { it.text.contains('　') } },
        ).isTrue()
    }

    @Test
    fun `paragraphs are split on blank lines and empty ones are dropped`() {
        val text = "第一章 起风\n\n甲段内容。\n\n\n\n乙段内容。\n\n\n丙段内容。"
        val paragraphs = doc(text).chapters.single().blocks.filterIsInstance<Block.Paragraph>()
        assertThat(paragraphs.map { it.spans.single().text })
            .containsExactly("甲段内容。", "乙段内容。", "丙段内容。").inOrder()
    }

    @Test
    fun `the table of contents mirrors the chapters in order and level`() {
        val document = doc(threeChapters)
        assertThat(document.toc.map { it.chapterIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(document.toc.map { it.title })
            .containsExactly("第一章 起风", "第二章 落雨", "第三章 归途").inOrder()
        assertThat(document.charTotal).isEqualTo(threeChapters.length)
    }

    @Test
    fun `an empty document still produces a chapter that can be laid out`() {
        val document = doc("")
        assertThat(document.chapters).hasSize(1)
        assertThat(document.charTotal).isEqualTo(0)
        assertThat(document.toc).hasSize(1)
    }

    /**
     * The whole pure-JVM reading path in one test: normalized text through to pages. If this holds,
     * the Android layer has nothing left to invent — it supplies a measurer and a canvas.
     */
    @Test
    fun `normalize, split, build and paginate produce pages that cover the chapter in order`() {
        val raw = "\uFEFF" + threeChapters.replace("\n\n", "\n\n\n\n")
        val normalized = TextNormalizer.normalizeText(raw)
        val document = doc(normalized)
        val chapter = document.chapters.first()

        val spec = PageSpec(pageWidth = 100f, pageHeight = 60f, fontSize = size, lineHeightMultiplier = 1.5f)
        val pages = Paginator.paginate(chapter, spec, FakeMeasurer(size)).pages

        assertThat(pages.size).isGreaterThan(1)
        assertThat(pages.first().firstChar).isEqualTo(0)
        for (index in 1 until pages.size) {
            assertThat(pages[index].firstChar).isAtLeast(pages[index - 1].lastChar)
        }

        val last = Paginator.anchorOf(pages, chapterIndex = 0, pagePosition = pages.size - 1)
        assertThat(Paginator.locate(pages, last)).isEqualTo(pages.size - 1)
        assertThat(last.percent(chapter.layoutCharCount)).isAtLeast(0f)
        assertThat(last.percent(chapter.layoutCharCount)).isAtMost(1f)
        // A mid-document page resolves to a strictly larger progress than the first page, which is
        // what makes percent usable as the cross-device position. The divisor is the *layout*
        // length: the slice is longer, because building blocks dropped indent markers.
        assertThat(chapter.layoutCharCount).isLessThan(chapter.charCount)
        val middle = Paginator.anchorOf(pages, chapterIndex = 0, pagePosition = 1)
        assertThat(middle.percent(chapter.layoutCharCount)).isGreaterThan(0f)
    }
}
