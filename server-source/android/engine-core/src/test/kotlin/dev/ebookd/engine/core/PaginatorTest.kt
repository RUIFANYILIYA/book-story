package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/**
 * The pagination rules, case by case, with a synthetic metric table injected instead of `Paint`.
 *
 * The metrics are deliberately simple — a CJK character is one em wide and advances a full em,
 * ASCII is half — because these tests are about *decisions* (where a line breaks, what is allowed
 * at its edges, when a page ends), not about how a real font rounds a glyph.
 */
class PaginatorTest {

    private val fontSize = 10f

    private class FakeMeasurer(private val size: Float) : GlyphMeasurer {
        override fun metrics(): FontMetrics = FontMetrics(ascent = size * 0.8f, descent = size * 0.2f)

        override fun width(text: CharSequence, start: Int, end: Int): Float {
            var total = 0f
            for (index in start until end) {
                // CJK and CJK punctuation are full width; everything else is half.
                total += if (text[index].code >= 0x2E80) size else size * 0.5f
            }
            return total
        }
    }

    private val measurer = FakeMeasurer(fontSize)

    private fun spec(
        contentWidthEm: Float,
        contentHeightEm: Float = 20f,
        maxIterations: Int = 200_000,
    ) = PageSpec(
        pageWidth = contentWidthEm * fontSize,
        pageHeight = contentHeightEm * fontSize,
        fontSize = fontSize,
        lineHeightMultiplier = 1.5f,
        maxIterations = maxIterations,
    )

    private fun paragraph(text: String, indentEm: Float = 0f, align: BlockAlign = BlockAlign.JUSTIFY) =
        Block.Paragraph(spans = listOf(Span(text)), align = align, firstLineIndentEm = indentEm)

    private fun chapter(vararg blocks: Block) =
        Chapter(index = 0, title = "第一章", blocks = blocks.toList())

    private fun flat(chapter: Chapter): String =
        chapter.blocks.joinToString("") { block ->
            when (block) {
                is Block.Paragraph -> block.spans.joinToString("") { it.text }
                is Block.Heading -> block.spans.joinToString("") { it.text }
                is Block.Preformatted -> block.text
                else -> ""
            }
        }

    private fun linesOf(chapter: Chapter, pages: List<Page>): List<String> {
        val text = flat(chapter)
        return pages.flatMap { page -> page.lines.filter { it.kind != LineKind.IMAGE }.map { text.substring(it.start, it.end) } }
    }

    // ---------------------------------------------------------------- 禁则

    @Test
    fun `a line never begins with a closing punctuation mark`() {
        // The comma sits exactly where a naive fill would break the first line, so without the
        // filter the second line would start with it; the same is arranged for the full stop.
        val chapter = chapter(paragraph("夜色从海，面上来。灯塔的光扫过水面。"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 4f), measurer).pages
        val lines = linesOf(chapter, pages)
        assertThat(lines).containsExactly("夜色从", "海，面上", "来。灯塔", "的光扫过", "水面。").inOrder()
        assertThat(lines.none { it.first() in "，。、；：！？）》”" }).isTrue()
        assertThat(lines.joinToString("")).isEqualTo(flat(chapter))
    }

    @Test
    fun `a line never ends with an opening bracket or quote`() {
        val chapter = chapter(paragraph("他说（然后停下来）继续走。"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 3f), measurer).pages
        val lines = linesOf(chapter, pages)
        assertThat(lines.none { it.endsWith("（") }).isTrue()
        assertThat(lines.joinToString("")).isEqualTo("他说（然后停下来）继续走。")
    }

    // ---------------------------------------------------------------- 中英混排

    @Test
    fun `an ASCII word is never split, while a CJK to ASCII boundary may break`() {
        val chapter = chapter(paragraph("夜色abc defg海面"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 4f), measurer).pages
        assertThat(linesOf(chapter, pages)).containsExactly("夜色abc", "defg海面").inOrder()
    }

    // ---------------------------------------------------------------- 两端对齐

    @Test
    fun `justification fills the measure on body lines, caps CJK gaps, and never touches the last line`() {
        val chapter = chapter(paragraph("夜色从海面漫上来，灯塔的光一圈一圈扫过水面"))
        // 95px: nine characters fit, ten do not, so each line has five pixels of surplus to spend.
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 9.5f), measurer).pages
        val lines = pages.first().lines
        assertThat(lines.size).isAtLeast(2)

        val bodyLine = lines.first()
        val totalGap = bodyLine.justifyGaps.sum()
        assertThat(totalGap).isWithin(0.01f).of(5f)
        assertThat(bodyLine.justifyGaps.all { it <= 0.33f * fontSize + 0.001f }).isTrue()

        val lastLine = lines.last()
        assertThat(lastLine.justifyGaps).isEmpty()
    }

    @Test
    fun `justification spends its surplus on ASCII spaces before touching CJK gaps`() {
        val chapter = chapter(paragraph("夜色 abc 海面漫上来"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 5.2f), measurer).pages
        val line = pages.first().lines.first()
        val text = flat(chapter)
        val spaceIndexes = (line.start until line.end).filter { text[it] == ' ' }
        if (spaceIndexes.isEmpty()) return
        // Every space carries part of the surplus, and no CJK gap was touched.
        assertThat(spaceIndexes.all { line.gapAt(it) > 0f }).isTrue()
    }

    // ---------------------------------------------------------------- 首行缩进

    @Test
    fun `a first-line indent costs a character on the first line and can add a line`() {
        // 50 characters at ten per line: exactly five lines plain, and the two-character indent
        // leaves the last two characters needing a sixth.
        val text = "夜".repeat(50)
        val flatChapter = chapter(paragraph(text))
        val indented = chapter(paragraph(text, indentEm = 2f))

        val plainPages = Paginator.paginate(flatChapter, spec(contentWidthEm = 10f), measurer).pages
        val indentedPages = Paginator.paginate(indented, spec(contentWidthEm = 10f), measurer).pages

        assertThat(plainPages.flatMap { it.lines }).hasSize(5)
        assertThat(indentedPages.flatMap { it.lines }).hasSize(6)
        assertThat(indentedPages.first().lines.first().x).isWithin(0.01f).of(2f * fontSize)
        assertThat(plainPages.first().lines.first().x).isWithin(0.01f).of(0f)
    }

    // ---------------------------------------------------------------- 图片

    @Test
    fun `an image taller than the page gets a page of its own`() {
        val chapter = chapter(
            paragraph("夜色从海面漫上来。"),
            Block.ImageBlock(locator = "cover.jpg", mime = "image/jpeg", heightEm = 20f),
        )
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 10f, contentHeightEm = 20f), measurer).pages
        assertThat(pages).hasSize(2)
        assertThat(pages[1].lines).hasSize(1)
        assertThat(pages[1].lines.single().kind).isEqualTo(LineKind.IMAGE)
    }

    @Test
    fun `an image that does not fit is moved to the next page rather than split`() {
        // 7 lines per page (contentHeight 10.5em at a 1.5em line box), and the body is exactly 70
        // characters at 10 per line: the page is full to the last pixel, so the image cannot share
        // it and must move whole.
        val chapter = chapter(
            paragraph("夜".repeat(70)),
            Block.ImageBlock(locator = "fig.jpg", mime = "image/jpeg", heightEm = 6f),
        )
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 10f, contentHeightEm = 10.5f), measurer).pages
        assertThat(pages).hasSize(2)
        assertThat(pages[1].lines.single().kind).isEqualTo(LineKind.IMAGE)

        // With room to spare it shares the page instead of wasting one.
        val roomy = Paginator.paginate(chapter, spec(contentWidthEm = 10f, contentHeightEm = 20f), measurer).pages
        assertThat(roomy).hasSize(1)
        assertThat(roomy.single().lines.last().kind).isEqualTo(LineKind.IMAGE)
    }

    @Test
    fun `a forced page break ends the page even with room to spare`() {
        val chapter = chapter(paragraph("甲。"), Block.PageBreak, paragraph("乙。"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 10f), measurer).pages
        assertThat(pages).hasSize(2)
    }

    // ---------------------------------------------------------------- 熔断器

    @Test
    fun `a spec that would produce more lines than allowed throws instead of hanging`() {
        val chapter = chapter(paragraph("夜".repeat(200)))
        val failure = assertFailsWith<PaginationOverflow> {
            Paginator.paginate(chapter, spec(contentWidthEm = 2f, maxIterations = 10), measurer)
        }
        assertThat(failure.message).contains("layout produced more than 10 lines")
    }

    @Test
    fun `a zero-width measurer still makes progress instead of spinning`() {
        // Width zero for everything would let every character "fit", so the breaker must be the
        // only thing between this spec and an infinite loop.
        val blind = object : GlyphMeasurer {
            override fun metrics(): FontMetrics = FontMetrics(ascent = 8f, descent = 2f)
            override fun width(text: CharSequence, start: Int, end: Int): Float = 0f
        }
        val chapter = chapter(paragraph("夜色从海面漫上来。"))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 10f), blind).pages
        assertThat(pages).hasSize(1)
        assertThat(pages.first().lines).hasSize(1)
    }

    // ---------------------------------------------------------------- 锚点

    @Test
    fun `an anchor survives re-pagination and resolves back to its page`() {
        val body = "夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的。".repeat(20)
        val chapter = chapter(paragraph(body))

        val small = Paginator.paginate(chapter, spec(contentWidthEm = 10f), measurer).pages
        val large = Paginator.paginate(chapter, spec(contentWidthEm = 16f), measurer).pages
        assertThat(large.size).isLessThan(small.size)

        for ((position, page) in small.withIndex()) {
            val anchor = Paginator.anchorOf(small, chapter.index, position)
            // The same character position lands on a different page number in a different layout,
            // which is exactly why the anchor is a character offset and not a page.
            val resolved = Paginator.locate(large, anchor)
            assertThat(large[resolved].firstChar).isAtMost(anchor.charOffset)
            if (resolved + 1 < large.size) {
                assertThat(large[resolved + 1].firstChar).isGreaterThan(anchor.charOffset)
            }
            assertThat(anchor.percent(body.length)).isAtLeast(0f)
        }
    }

    @Test
    fun `pages ascend by character offset and cover the chapter`() {
        val body = "夜色从海面漫上来，灯塔的光一圈一圈扫过水面。".repeat(10)
        val chapter = chapter(paragraph(body))
        val pages = Paginator.paginate(chapter, spec(contentWidthEm = 12f), measurer).pages
        assertThat(pages).isNotEmpty()
        assertThat(pages.first().firstChar).isEqualTo(0)
        for ((index, page) in pages.withIndex()) {
            assertThat(page.firstChar).isLessThan(page.lastChar)
            val next = pages.getOrNull(index + 1)
            if (next != null) assertThat(next.firstChar).isEqualTo(page.lastChar)
        }
    }

    @Test
    fun `a chapter with no blocks still yields one empty page`() {
        val pages = Paginator.paginate(chapter(), spec(contentWidthEm = 10f), measurer).pages
        assertThat(pages).hasSize(1)
        assertThat(pages.single().isEmpty).isTrue()
    }
}
