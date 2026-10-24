package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The chapter splitter's behaviour, case by case.
 *
 * These read as a specification because that is what they are: the algorithm is a set of judgement
 * calls, and each test names the judgement it protects. The two failures that matter are opposite
 * in kind — a missed heading makes the table of contents coarse but honest, while an invented one
 * puts a line of prose in the navigation where the reader will tap it — so most of what follows
 * pins the *rejections*, not the detections.
 *
 * The case ids mirror shared/fixtures/txt-chapters/README.md, which is the cross-language contract:
 * the same inputs are meant to run under the server's TypeScript splitter so the two cannot drift
 * on what counts as a chapter.
 */
class TxtChapterSplitterTest {

    private fun titles(text: String, options: SplitOptions = SplitOptions()) =
        TxtChapterSplitter.split(text, options).marks.map { it.title }

    private fun chapters(text: String) =
        TxtChapterSplitter.split(text).marks.mapIndexed { index, mark ->
            val end = TxtChapterSplitter.split(text).marks.getOrNull(index + 1)?.startChar ?: text.length
            text.substring(mark.startChar, end)
        }

    // ---------------------------------------------------------------- 01 网文

    @Test
    fun `splits a numbered web novel and keeps the prologue numbered zero`() {
        val text = """
            第一章 风雪夜

            夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的。这一段是为测试写的中文正文，用来把章节撑到合理的长度。渡口空无一人，只有缆绳拍打船身的声音。

            第二章 渡口

            第二天清晨，渡船靠岸，雾气还没有散尽。他站在堤岸上，看着远处灰白色的水面，手心里全是汗。这一段同样是测试正文，长度足以让它不被当作过短章节而被合并。

            第三章 归途

            傍晚时分，他沿着堤岸往回走，脚下的碎石发出细碎的声响。天边的云被染成了暗红色，像一块正在冷却的铁。测试正文到此结束，这一段的长度也足够。
        """.trimIndent()

        assertThat(titles(text)).containsExactly("第一章 风雪夜", "第二章 渡口", "第三章 归途").inOrder()
    }

    // ---------------------------------------------------------------- 04 目录区

    @Test
    fun `excludes a table of contents region from the body and uses it as evidence`() {
        val text = """
            目录

            第一章 起风 ........ 1
            第二章 落雨 ........ 18
            第三章 转晴 ........ 40


            第一章 起风

            这一段是第一章的正文，长度足够，用来说明目录区被正确排除在正文之外，不会被当成章节重复计入。

            第二章 落雨

            这一段是第二章的正文，同样有足够的长度，确保它不会被过短的规则合并到上一章里去。

            第三章 转晴

            这是第三章的正文，天气终于放晴，故事在这里收束，测试也就到这里结束了。
        """.trimIndent()

        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks.map { it.title }).containsExactly("第一章 起风", "第二章 落雨", "第三章 转晴").inOrder()
        // The hint from the contents block is what makes this reliable, so the marks say so.
        assertThat(result.marks.all { it.source == MarkSource.TOC_HINT }).isTrue()
        assertThat(result.warnings.joinToString()).contains("目录区")
    }

    // ---------------------------------------------------------------- 09 假阳性诱饵

    @Test
    fun `rejects prose that merely mentions a chapter number`() {
        val text = """
            他想起第三章说过的话，心里忽然一沉。窗外的雨还在下，敲在玻璃上，像有人在轻轻叩门。这一段没有任何标题，只是一段普通的叙述文字，长度足够让它自成一章。

            1. 首先把水烧开，然后放入茶叶，等三分钟再倒出来。这一段看起来像编号标题，其实是一段正文，用来验证编号规则不会把普通句子切成一章。

            他合上书，走到窗前。远处的灯火一盏一盏熄灭，城市慢慢安静下来。这一段依然是正文，没有任何章节标记，长度也足够。
        """.trimIndent()

        val result = TxtChapterSplitter.split(text)
        // The honest outcome for a text with no headings is a length-based split, not a fictional
        // table of contents built out of sentences.
        assertThat(result.marks).isNotEmpty()
        assertThat(result.marks.all { it.source == MarkSource.LENGTH }).isTrue()
        assertThat(result.warnings.joinToString()).contains("未识别到章节标题")
    }

    @Test
    fun `rejects a whole numbering family whose numbers do not behave like numbers`() {
        val padding = "这一段用来把每个可疑标题之间的内容撑开，使得它们不会被过短的规则先合并掉，从而真正走到编号校验那一步。"
        val text = buildString {
            appendLine("第三章 甲")
            appendLine()
            appendLine(padding.repeat(3))
            appendLine()
            appendLine("第七章 乙")
            appendLine()
            appendLine(padding.repeat(3))
            appendLine()
            appendLine("第二章 丙")
            appendLine()
            appendLine(padding.repeat(3))
        }

        val result = TxtChapterSplitter.split(text)
        // 3, 7, 2 is not a numbered sequence, so the family is rejected wholesale rather than
        // producing a table of contents that looks plausible and is fiction.
        assertThat(result.warnings.joinToString()).contains("章号不连续")
        assertThat(result.marks.all { it.source == MarkSource.LENGTH }).isTrue()
    }

    // ---------------------------------------------------------------- 08 页眉页脚

    @Test
    fun `does not turn page numbers into chapters`() {
        // Long enough per chapter that the short-chapter rule does not fold the second one away;
        // this test is about page furniture being rejected, not about that rule.
        val body = "这是正文的一段内容，用来模拟电子书里夹着页码与书眉的情况。它的长度足以让所在章节不被当作过短章节合并，" +
            "从而真正检验页码、书眉与分隔符是否被正确忽略。这样的段落在一本真实的电子书里随处可见，" +
            "因此用它作为测试数据也更接近实际情况。"
        val text = buildString {
            appendLine("第一章 起风")
            appendLine()
            appendLine(body)
            appendLine("12")
            appendLine()
            appendLine(body)
            appendLine("第 13 页")
            appendLine()
            appendLine(body)
            appendLine("----")
            appendLine()
            appendLine("第二章 落雨")
            appendLine()
            appendLine(body)
        }

        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks.map { it.title }).containsExactly("第一章 起风", "第二章 落雨").inOrder()
        assertThat(result.marks.none { it.title.trim().toIntOrNull() != null }).isTrue()
    }

    // ---------------------------------------------------------------- 03 英文

    @Test
    fun `splits english chapter headings`() {
        // Chapters are padded past minChapterChars on purpose: the first version of this test used
        // ~190-character chapters and failed, because the short-chapter rule correctly folded them
        // into the previous one. The rule was right and the fixture was unrealistic.
        val body = "The harbour was quiet that morning, and the water barely moved against the stones. " +
            "This paragraph exists only to give the chapter enough length that the short-chapter rule " +
            "does not fold it away, which is what a real chapter of a real book would provide anyway."
        val text = """
            Chapter 1

            $body

            Chapter 2

            $body

            Epilogue

            $body
        """.trimIndent()

        assertThat(titles(text)).containsExactly("Chapter 1", "Chapter 2", "Epilogue").inOrder()
    }

    // ---------------------------------------------------------------- 05 无标题

    @Test
    fun `falls back to length splitting and cuts on paragraph boundaries`() {
        val paragraph = "这是一段用于填充的中文正文，长度大约七十个字符左右，反复出现以构成一篇没有标题的长文。" +
            "它的存在只是为了验证长度兜底切分不会切在句子中间，而是落在段落边界上。"
        val text = (1..400).joinToString("\n\n") { paragraph }

        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks.size).isAtLeast(2)
        assertThat(result.marks.all { it.source == MarkSource.LENGTH }).isTrue()
        // Every cut lands after a blank line, so no chapter begins mid-sentence.
        for (mark in result.marks.drop(1)) {
            val preceding = text.substring((mark.startChar - 2).coerceAtLeast(0), mark.startChar)
            assertThat(preceding).isEqualTo("\n\n")
        }
        assertThat(result.marks.first().startChar).isEqualTo(0)
    }

    // ---------------------------------------------------------------- modes

    @Test
    fun `blank-line mode is predictable and single mode is one chapter`() {
        val text = "第一块\n\n第二块\n\n第三块"
        assertThat(titles(text, SplitOptions(mode = SplitMode.BLANK_LINE)))
            .containsExactly("第一块", "第二块", "第三块").inOrder()
        assertThat(titles(text, SplitOptions(mode = SplitMode.SINGLE))).containsExactly("全书")
    }

    @Test
    fun `a custom pattern decides alone, and an invalid one is reported rather than ignored`() {
        val text = "=== 甲 ===\n正文一\n=== 乙 ===\n正文二"
        val custom = SplitOptions(mode = SplitMode.CUSTOM, customPattern = "^===.*===$")
        assertThat(titles(text, custom)).containsExactly("=== 甲 ===", "=== 乙 ===").inOrder()

        val broken = TxtChapterSplitter.split(text, SplitOptions(mode = SplitMode.CUSTOM, customPattern = "(["))
        assertThat(broken.warnings.joinToString()).contains("无效")
        assertThat(broken.marks).hasSize(1)
    }

    // ---------------------------------------------------------------- determinism & merging

    @Test
    fun `is deterministic and its hash changes when the inputs change`() {
        val text = "第一章 甲\n\n${"正文内容。".repeat(60)}\n\n第二章 乙\n\n${"更多正文。".repeat(60)}"
        val first = TxtChapterSplitter.split(text)
        val second = TxtChapterSplitter.split(text)
        assertThat(first).isEqualTo(second)

        val options = SplitOptions()
        assertThat(first.optsHash(options)).isEqualTo(second.optsHash(options))
        // A different mode must not be able to reuse the cached split: the offsets would differ and
        // the reader's saved position would point somewhere else.
        assertThat(first.optsHash(SplitOptions(mode = SplitMode.BLANK_LINE)))
            .isNotEqualTo(first.optsHash(options))
    }

    @Test
    fun `folds an over-short heading into the previous chapter`() {
        val body = "正文内容足够长，用来保证这一章不会被当作过短章节合并掉。".repeat(6)
        val text = "第一章 甲\n\n$body\n\n第二章 乙\n\n只有一句。"
        val result = TxtChapterSplitter.split(text)
        // The trailing stub is data, not a chapter; keeping it would put a one-line entry in the
        // navigation that goes nowhere.
        assertThat(result.marks.map { it.title }).containsExactly("第一章 甲")
        assertThat(result.warnings.joinToString()).contains("过短")
    }

    @Test
    fun `does not treat consecutively numbered headings as duplicates of each other`() {
        // Regression: dedupe used a fuzzy comparison meant for matching contents entries, and
        // `第一章`/`第二章` differ by one character, so every chapter but the first was deleted.
        // The result looked like a deliberate one-entry table of contents, which is why it survived
        // review — the bug is invisible unless the chapter count is asserted.
        val body = "正文内容需要足够长，以避免被过短章节规则合并，这样才能验证相邻编号的标题都会保留下来。" +
            "这一段反复出现只是为了凑够长度。"
        val text = (1..5).joinToString("\n\n") { "第${"一二三四五"[it - 1]}章 测试" + "\n\n" + body.repeat(3) }

        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks).hasSize(5)
        assertThat(result.marks.map { it.title }).containsExactly(
            "第一章 测试", "第二章 测试", "第三章 测试", "第四章 测试", "第五章 测试",
        ).inOrder()
    }

    @Test
    fun `an empty document still produces one chapter rather than none`() {
        val result = TxtChapterSplitter.split("")
        assertThat(result.marks).hasSize(1)
        assertThat(result.marks.first().source).isEqualTo(MarkSource.WHOLE_DOCUMENT)
    }

    @Test
    fun `chapter offsets cover the text without gaps or overlaps`() {
        val text = "第一章 甲\n\n${"正文。".repeat(80)}\n\n第二章 乙\n\n${"正文。".repeat(80)}"
        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks.first().startChar).isEqualTo(0)
        for ((index, mark) in result.marks.withIndex()) {
            val next = result.marks.getOrNull(index + 1)
            if (next != null) assertThat(mark.startChar).isLessThan(next.startChar)
        }
        // The offsets are the anchors a saved position is resolved against, so they must lie
        // inside the text and be strictly ordered.
        assertThat(result.marks.all { it.startChar in 0..text.length }).isTrue()
    }
}
