package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * The cross-language golden cases in `shared/fixtures/txt-chapters/`, run through the *whole* TXT
 * pipeline: bytes → charset → normalization → split.
 *
 * Running the whole pipeline rather than the splitter alone is the point. The expected `startChar`
 * values are offsets into the *normalized* text, so a change to normalization that moves a single
 * character — a page number no longer stripped, a blank run collapsed differently — shows up here
 * as a shifted anchor, which is exactly the failure a reader would experience as "my bookmark moved
 * to the wrong paragraph after the update".
 *
 * The expectations are data, not code: `expected/<id>.json` is reviewed and committed, and this
 * test is the thing that refuses to let them drift. When a file is missing it writes a proposal
 * instead of inventing a pass, so bootstrapping a new case cannot silently fabricate its own truth.
 */
class TxtChapterSplitterFixtureTest {

    @Serializable
    private data class ExpectedChapter(val title: String, val level: Int, val startChar: Int)

    @Serializable
    private data class Expected(
        val id: String,
        val splitMode: String,
        val charset: String,
        val chapters: List<ExpectedChapter>,
        val chapterCount: Int,
        /** Substrings that must appear in the split's warnings; empty means "not asserted". */
        val warnings: List<String> = emptyList(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    private val fixtures = File(
        System.getProperty("ebookd.fixtures")
            ?: fail("ebookd.fixtures system property is not set; run through Gradle"),
    ).resolve("txt-chapters")

    private val proposals = File("build/fixture-proposals")

    @Test
    fun `every committed case decodes, normalizes and splits to its recorded expectation`() {
        val cases = fixtures.resolve("cases").listFiles { file -> file.extension == "txt" }
            ?.sortedBy { it.name }
            .orEmpty()
        assertThat(cases).isNotEmpty()

        val missing = mutableListOf<String>()
        for (case in cases) {
            val id = case.nameWithoutExtension
            val detected = CharsetDetector.detect(case.readBytes())
            val normalized = TextNormalizer.normalize(detected.text)
            val split = TxtChapterSplitter.split(normalized.text)
            // Normalizer warnings count as results too: "page furniture was stripped" is the claim
            // case 08 exists to make, and a case that silently stopped stripping would otherwise
            // still produce a plausible chapter list.
            val warnings = normalized.warnings + split.warnings

            val expectedFile = fixtures.resolve("expected/$id.json")
            if (!expectedFile.exists()) {
                propose(id, detected.charset, split, warnings)
                missing += id
                continue
            }

            val expected = json.decodeFromString<Expected>(expectedFile.readText())
            assertThat(expected.id).isEqualTo(id)
            // The charset is asserted here even though the server has its own detector: a
            // disagreement between the two would mean one of them renders the book as mojibake.
            assertThat(detected.charset).isEqualTo(expected.charset)
            assertThat(split.mode.name).isEqualTo(expected.splitMode)
            assertThat(split.marks).hasSize(expected.chapterCount)
            assertThat(expected.chapters).hasSize(expected.chapterCount)
            split.marks.forEachIndexed { index, mark ->
                val chapter = expected.chapters[index]
                assertThat(mark.startChar).isEqualTo(chapter.startChar)
                assertThat(mark.title).isEqualTo(chapter.title)
                assertThat(mark.level).isEqualTo(chapter.level)
                // An anchor a reader can navigate to must point at the title it names, or a saved
                // position resolves to a different paragraph than the table of contents promised.
                // Length-based marks are excluded because their titles are generated ("全文"), not
                // quoted from the text.
                if (mark.source == MarkSource.HEADING || mark.source == MarkSource.TOC_HINT) {
                    assertThat(normalized.text.startsWith(mark.title, mark.startChar)).isTrue()
                }
            }
            for (warning in expected.warnings) {
                // Substring, not equality: the fixture says "page headers were stripped", not the
                // exact sentence the current implementation happens to print.
                assertThat(warnings.joinToString(" | ")).contains(warning)
            }
        }

        if (missing.isNotEmpty()) {
            fail(
                "no expectation recorded for ${missing.joinToString()} — proposals were written to " +
                    "${proposals.absolutePath}; review them against the case text, move them to " +
                    "shared/fixtures/txt-chapters/expected/, and re-run",
            )
        }
    }

    private fun propose(id: String, charset: String, split: SplitResult, warnings: List<String>) {
        proposals.mkdirs()
        val expected = Expected(
            id = id,
            splitMode = split.mode.name,
            charset = charset,
            chapters = split.marks.map { ExpectedChapter(it.title, it.level, it.startChar) },
            chapterCount = split.marks.size,
            warnings = warnings,
        )
        proposals.resolve("$id.json").writeText(json.encodeToString(Expected.serializer(), expected))
    }

    /**
     * Case 05 is generated rather than committed: a 30 MB blob does not belong in git history, and
     * the recipe is deterministic. What it protects is the fallback path for a text with no
     * headings at all — the case where the reader most needs navigation and has the least to go on.
     */
    @Test
    fun `a headingless 30 MB text falls back to length splitting on paragraph boundaries`() {
        val paragraph = "这是一段用于填充的中文正文，长度大约七十个字符左右，反复出现以构成一篇没有标题的长文。" +
            "它的存在只是为了验证长度兜底切分不会切在句子中间，而是落在段落边界上。\n\n"
        val paragraphs = 30 * 1024 * 1024 / paragraph.toByteArray(Charsets.UTF_8).size
        val text = paragraph.repeat(paragraphs)

        val result = TxtChapterSplitter.split(text)
        assertThat(result.marks.all { it.source == MarkSource.LENGTH }).isTrue()
        assertThat(result.marks.size).isAtLeast(100)
        assertThat(result.marks.first().startChar).isEqualTo(0)
        for ((index, mark) in result.marks.withIndex()) {
            val next = result.marks.getOrNull(index + 1)?.startChar ?: text.length
            assertThat(mark.startChar).isLessThan(next)
            // Everything the fallback claims: cuts land after a blank line, and the pieces stay
            // inside the window the contract promises rather than degenerating into stubs.
            if (index > 0) assertThat(text.substring(mark.startChar - 2, mark.startChar)).isEqualTo("\n\n")
            assertThat(next - mark.startChar).isAtLeast(8_000)
            assertThat(next - mark.startChar).isAtMost(60_000)
        }
    }
}
