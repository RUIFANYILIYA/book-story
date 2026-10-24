package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Normalization is where a converted file becomes a book, and also where a careless pass can
 * destroy one. The tests below pin both directions: the joins that make hard-wrapped text
 * readable, and the guards that stop a refrain from being deleted or a heading from being
 * swallowed by the paragraph above it.
 */
class TextNormalizerTest {

    private fun normalize(raw: String) = TextNormalizer.normalize(raw).text

    @Test
    fun `line endings, BOM and tabs are unified before anything else looks at the text`() {
        val raw = "\uFEFF第一章\r\n\r\n\t正文一\r\n\r\n正文二\r\n"
        val text = normalize(raw)
        assertThat(text).doesNotContain("\r")
        assertThat(text).doesNotContain("\uFEFF")
        assertThat(text).doesNotContain("\t")
        // A tab is indentation, so it lands on the indent marker rather than on four raw spaces.
        assertThat(text).contains("\u3000正文一")
    }

    @Test
    fun `indentation collapses to a single marker, and a stray single space is not indentation`() {
        assertThat(normalize("　　第一章")).isEqualTo("\u3000第一章")
        assertThat(normalize("　　　　正文")).isEqualTo("\u3000正文")
        assertThat(normalize("    正文")).isEqualTo("\u3000正文")
        // One leading space is a conversion artefact, not an indent; honouring it would block the
        // reflow pass from joining this line to its paragraph.
        assertThat(normalize(" 正文")).isEqualTo("正文")
    }

    @Test
    fun `three or more blank lines become one, two survive as a scene break, and the file is trimmed`() {
        assertThat(normalize("甲\n\n\n\n乙")).isEqualTo("甲\n\n乙")
        assertThat(normalize("甲\n\n\n\n\n\n乙")).isEqualTo("甲\n\n乙")
        assertThat(normalize("甲\n\n\n乙")).isEqualTo("甲\n\n\n乙")
        assertThat(normalize("甲\n\n乙")).isEqualTo("甲\n\n乙")
        assertThat(normalize("\n\n\n甲")).isEqualTo("甲")
    }

    @Test
    fun `page numbers that increment are still one repeating shape and get stripped`() {
        val pages = (1..10).joinToString("\n") { number ->
            "ebookd 测试书\n正文一。\n$number\n第 $number 页\n"
        }
        val result = TextNormalizer.normalize(pages)
        assertThat(result.text).doesNotContain("ebookd 测试书")
        assertThat(result.text).doesNotContain("第 ")
        assertThat(result.text.lines().any { it.trim().toIntOrNull() != null }).isFalse()
        assertThat(result.warnings.joinToString()).contains("页眉页脚")
        // The body, which ends a sentence, is never furniture no matter how often it repeats.
        assertThat(result.text).contains("正文一。")
    }

    @Test
    fun `a refrain repeated in one place is content, not a running header`() {
        // Nine occurrences, which clears the count threshold, but they sit next to each other
        // instead of being spread across the file - that is a chorus, and deleting it would be
        // the kind of silent data loss a reader cannot even report. The blank lines keep the
        // reflow pass from gluing the verses together, which is a separate behaviour.
        val padding = "这是一段用来把文件撑长的正文，它本身不会被剥离。"
        val text = buildString {
            repeat(20) { appendLine(padding); appendLine() }
            repeat(9) { appendLine("风起了"); appendLine() }
            repeat(20) { appendLine(padding); appendLine() }
        }
        assertThat(normalize(text).lines().count { it == "风起了" }).isEqualTo(9)
    }

    @Test
    fun `hard-wrapped Chinese lines are joined without inserting spaces`() {
        val raw = "他站在堤岸上，看着远处灰白色的水面，\n手心里全是汗。\n\n下一段。"
        assertThat(normalize(raw)).isEqualTo("他站在堤岸上，看着远处灰白色的水面，手心里全是汗。\n\n下一段。")
    }

    @Test
    fun `hard-wrapped English lines are joined with the space back`() {
        val raw = "The harbour was quiet that morning, and the water\nbarely moved against the stones."
        assertThat(normalize(raw))
            .isEqualTo("The harbour was quiet that morning, and the water barely moved against the stones.")
    }

    @Test
    fun `a paragraph that finished its sentence is not joined to the next one`() {
        val raw = "他没有再说话。\n第二天清晨，渡船靠岸。"
        assertThat(normalize(raw)).isEqualTo(raw)
    }

    @Test
    fun `an indented line starts a paragraph and a heading is never swallowed`() {
        val indented = "\u3000\u3000甲\n\u3000\u3000乙"
        assertThat(normalize(indented)).isEqualTo("\u3000甲\n\u3000乙")

        val heading = "他合上书，走到窗前\n第一章 风雪夜\n\n正文。"
        assertThat(normalize(heading)).isEqualTo("他合上书，走到窗前\n第一章 风雪夜\n\n正文。")
    }

    @Test
    fun `normalization is idempotent, offsets do not move on a second pass`() {
        val raw = "\uFEFF第一章 风雪夜\r\n\r\n\t夜色从海面漫上来，灯塔的光一圈一圈扫过水面，\r\n" +
            "风把碎雪吹得斜斜的。\r\n\r\n\r\n\r\n　　第二章 渡口\r\n\r\n第二天清晨，\r\n渡船靠岸。\r\n12\r\n"
        val once = TextNormalizer.normalize(raw)
        val twice = TextNormalizer.normalize(once.text)
        assertThat(twice.text).isEqualTo(once.text)
    }

    @Test
    fun `whitespace-only input normalizes to nothing`() {
        assertThat(normalize("  \n\n\n\t\n")).isEmpty()
    }
}
